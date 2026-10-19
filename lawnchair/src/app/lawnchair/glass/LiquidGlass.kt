package app.lawnchair.glass

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.wallpaper.WallpaperManagerCompat
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Vidro líquido: desenha a base de vidro dos ícones lendo o papel de parede real
 * atrás de cada ícone, com desfoque, refração nas bordas e reflexos de luz.
 * A distorção é recalculada sempre que o ícone muda de posição na tela.
 */
object LiquidGlass {

    /** Liga/desliga o efeito. */
    @JvmField var enabled = true

    /** Tamanho do símbolo do app dentro do vidro (0..1). */
    const val GLYPH_SCALE = 0.62f

    private const val AGSL = """
        uniform shader wall;
        uniform float2 size;
        uniform float2 offset;
        uniform float hasWall;
        uniform float refractPx;
        uniform float darken;

        half4 main(float2 p) {
            float2 c = size * 0.5;
            float2 q = (p - c) / c;
            float n = 5.0;
            float d = pow(pow(abs(q.x), n) + pow(abs(q.y), n), 1.0 / n);
            float px = 1.0 / c.x;
            float aa = clamp((1.0 - d) / (1.5 * px), 0.0, 1.0);
            if (aa <= 0.0) { return half4(0.0); }

            float2 g = sign(q) * pow(abs(q) + 1e-4, float2(n - 1.0));
            float2 nrm = normalize(g + float2(1e-5));
            float e = (1.0 - d) * c.x;
            float bevel = size.x * 0.18;
            float t = clamp(1.0 - e / bevel, 0.0, 1.0);
            float bend = t * t * t;

            half3 col;
            if (hasWall > 0.5) {
                float2 base = p + offset;
                float2 dsp = -nrm * bend * refractPx;
                col.r = wall.eval(base + dsp * 1.10).r;
                col.g = wall.eval(base + dsp).g;
                col.b = wall.eval(base + dsp * 0.90).b;
            } else {
                col = half3(0.09, 0.10, 0.13);
            }

            float l = dot(col, half3(0.299, 0.587, 0.114));
            col = mix(half3(l), col, 1.35);
            col *= darken;

            float yy = p.y / size.y;
            col += half3(0.05) * (1.0 - yy);

            float L1 = clamp(dot(nrm, normalize(float2(-0.6, -0.8))), 0.0, 1.0);
            float L2 = clamp(dot(nrm, normalize(float2(0.7, 0.7))), 0.0, 1.0);
            float band = exp(-e / (size.x * 0.03));
            col += half3(1.0) * band * (0.05 + 0.50 * pow(L1, 2.0) + 0.28 * L2 * L2);
            col -= half3(0.10) * band * clamp(nrm.y * 0.9 + nrm.x * 0.3, 0.0, 1.0);
            float rim = exp(-e / 1.1);
            col += half3(1.0) * rim * (0.22 + 0.45 * L1 + 0.30 * L2);

            col = clamp(col, 0.0, 1.0);
            return half4(col * aa, aa);
        }
    """

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var wallShader: BitmapShader? = null
    private var shader: RuntimeShader? = null
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x55FFFFFF
    }
    private val path = Path()
    private val listeners = mutableSetOf<Runnable>()
    private var loading = false

    @JvmStatic
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        WallpaperManagerCompat.INSTANCE.get(context)
            .addOnChangeListener(object : WallpaperManagerCompat.OnColorsChangedListener {
                override fun onColorsChanged() = reload()
            })
        reload()
    }

    /** Chamado pelos ícones para serem redesenhados quando o papel de parede mudar. */
    @JvmStatic fun addListener(r: Runnable) { listeners.add(r) }
    @JvmStatic fun removeListener(r: Runnable) { listeners.remove(r) }

    @JvmStatic
    fun reload() {
        val ctx = appContext ?: return
        if (loading) return
        loading = true
        worker.execute {
            val bmp = loadBlurredWallpaper(ctx)
            main.post {
                loading = false
                applyWallpaper(ctx, bmp)
                listeners.toList().forEach { it.run() }
            }
        }
    }

    /** Ao voltar para a tela inicial, recarrega se a permissão foi concedida depois. */
    @JvmStatic
    fun onResume() {
        if (appContext != null && wallShader == null && hasFileAccess()) reload()
    }

    private fun hasFileAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    @SuppressLint("MissingPermission")
    private fun loadBlurredWallpaper(ctx: Context): Bitmap? {
        if (!hasFileAccess()) return null
        return try {
            val wm = WallpaperManager.getInstance(ctx)
            if (wm.wallpaperInfo != null) return null // papel de parede animado: sem imagem estática
            val drawable = wm.drawable ?: return null
            val src = drawable.toBitmap()
            val target = 120
            val s = target.toFloat() / min(src.width, src.height)
            val small = Bitmap.createScaledBitmap(
                src, max(1, (src.width * s).toInt()), max(1, (src.height * s).toInt()), true,
            ).copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(small, 2)
            boxBlur(small, 2)
            boxBlur(small, 2)
            small
        } catch (t: Throwable) {
            null
        }
    }

    private fun applyWallpaper(ctx: Context, bmp: Bitmap?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (bmp == null) {
            wallShader = null
            shader = RuntimeShader(AGSL).also {
                it.setInputShader("wall", BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                it.setFloatUniform("hasWall", 0f)
            }
            return
        }
        val bounds = (ctx.getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val s = max(w / bmp.width, h / bmp.height)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f)
        }
        val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(m)
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        wallShader = bs
        shader = RuntimeShader(AGSL).also {
            it.setInputShader("wall", bs)
            it.setFloatUniform("hasWall", 1f)
        }
    }


    /**
     * Desenha a base de vidro em [r] (coordenadas locais da view).
     * [screenX]/[screenY] são a posição da view na tela.
     */
    @JvmStatic
    fun draw(canvas: Canvas, r: Rect, screenX: Int, screenY: Int) {
        val sh = shader
        val size = r.width().toFloat()
        if (sh != null && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sh.setFloatUniform("size", size, r.height().toFloat())
            sh.setFloatUniform("offset", (screenX + r.left).toFloat(), (screenY + r.top).toFloat())
            sh.setFloatUniform("refractPx", size * 0.22f)
            sh.setFloatUniform("darken", 0.62f)
            shaderPaint.shader = sh
            val c = canvas.save()
            canvas.translate(r.left.toFloat(), r.top.toFloat())
            canvas.drawRect(0f, 0f, size, r.height().toFloat(), shaderPaint)
            canvas.restoreToCount(c)
        } else {
            // Fallback (sem aceleração): vidro escuro simples com borda clara.
            val rf = RectF(r)
            val rad = size * 0.26f
            path.reset()
            path.addRoundRect(rf, rad, rad, Path.Direction.CW)
            fallbackPaint.color = 0x66101318
            canvas.drawPath(path, fallbackPaint)
            rf.inset(1f, 1f)
            canvas.drawRoundRect(rf, rad, rad, rimPaint)
        }
    }

    /** Desfoque em caixa simples (in-place). */
    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
            val outer = if (horizontal) h else w
            val inner = if (horizontal) w else h
            for (o in 0 until outer) {
                for (i in 0 until inner) {
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (k in -radius..radius) {
                        val j = (i + k).coerceIn(0, inner - 1)
                        val idx = if (horizontal) o * w + j else j * w + o
                        val c = src[idx]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                    }
                    val idx = if (horizontal) o * w + i else i * w + o
                    dst[idx] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
        pass(px, tmp, true); pass(tmp, px, false)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
