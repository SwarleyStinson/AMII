package io.unthrottled.amii.memes

import com.intellij.notification.impl.NotificationsManagerImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JreHiDpiUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.HwFacadeJPanel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.Alarm
import com.intellij.util.ui.Animator
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.UIUtil
import io.unthrottled.amii.assets.VisualMemeAsset
import io.unthrottled.amii.config.ui.NotificationAnchor
import io.unthrottled.amii.config.ui.NotificationAnchor.BOTTOM_CENTER
import io.unthrottled.amii.config.ui.NotificationAnchor.BOTTOM_LEFT
import io.unthrottled.amii.config.ui.NotificationAnchor.BOTTOM_RIGHT
import io.unthrottled.amii.config.ui.NotificationAnchor.CENTER
import io.unthrottled.amii.config.ui.NotificationAnchor.MIDDLE_LEFT
import io.unthrottled.amii.config.ui.NotificationAnchor.TOP_CENTER
import io.unthrottled.amii.config.ui.NotificationAnchor.TOP_LEFT
import io.unthrottled.amii.config.ui.NotificationAnchor.TOP_RIGHT
import io.unthrottled.amii.memes.PanelDismissalOptions.FOCUS_LOSS
import io.unthrottled.amii.memes.PanelDismissalOptions.TIMED
import io.unthrottled.amii.services.GifService
import io.unthrottled.amii.tools.Logging
import io.unthrottled.amii.tools.runSafelyWithResult
import java.awt.AWTEvent.KEY_EVENT_MASK
import java.awt.AWTEvent.MOUSE_EVENT_MASK
import java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.awt.image.RGBImageFilter
import javax.swing.JLayeredPane
import javax.swing.MenuElement
import javax.swing.SwingUtilities

enum class PanelDismissalOptions {
  FOCUS_LOSS, TIMED;

  companion object {
    fun fromValue(value: String): PanelDismissalOptions =
      runSafelyWithResult({
        valueOf(value)
      }) { TIMED }
  }
}

data class MemePanelSettings(
  val dismissal: PanelDismissalOptions,
  val anchor: NotificationAnchor
)

// todo: make smoother transition animation
@Suppress("TooManyFunctions")
class MemePanel(
  private val rootPane: JLayeredPane,
  private val visualMeme: VisualMemeAsset,
  private val memePanelSettings: MemePanelSettings
) : HwFacadeJPanel(), Disposable, Logging {

  companion object {
    private const val TOTAL_FRAMES = 8
    private const val CYCLE_DURATION = 500
    private const val PANEL_PADDING = 10
    private const val NOTIFICATION_Y_OFFSET = 10
    private const val HALF_DIVISOR = 2
    private const val fadeoutDelay = 100
    private const val MEME_DISPLAY_LIFETIME = 3000
    private const val CLEARED_ALPHA = -1f
    private const val WHITE_HEX = 0x00FFFFFF
  }

  private var alpha = 0.0f
  private var overlay: BufferedImage? = null

  private var invulnerable = true

  private val fadeoutAlarm = Alarm(this)
  private val mouseListener: AWTEventListener

  init {
    isOpaque = false
    clear()

    val memeContent = createMemeContentPanel()
    add(memeContent)
    val memeSize = memeContent.preferredSize
    val width = memeSize.width + PANEL_PADDING
    val height = memeSize.height + PANEL_PADDING
    memeContent.size = Dimension(width, height)
    this.size = Dimension(width, height)
    this.background = UIUtil.getPanelBackground()
    this.border = JBUI.Borders.customLine(
      JBColor.namedColor(
        "Notification.borderColor",
        NotificationsManagerImpl.BORDER_COLOR
      )
    )
    positionMemePanel(
      memePanelSettings,
      memeContent.preferredSize.width,
      memeContent.preferredSize.height,
    )

    mouseListener = createMouseLister()
    Toolkit.getDefaultToolkit().addAWTEventListener(
      mouseListener,
      MOUSE_EVENT_MASK or MOUSE_MOTION_EVENT_MASK or KEY_EVENT_MASK
    )
  }

  private fun createMouseLister() =
    AWTEventListener { e ->
      if (invulnerable) return@AWTEventListener

      if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED) {
        if (!isInsideMemePanel(e) && memePanelSettings.dismissal == FOCUS_LOSS) {
          fadeoutAlarm.cancelAllRequests()
          fadeoutAlarm.addRequest({ runAnimation(false) }, fadeoutDelay)
        }
      } else if (e is KeyEvent && e.id == KeyEvent.KEY_PRESSED) {
        when (e.keyCode) {
          KeyEvent.VK_ESCAPE -> {
            fadeoutAlarm.cancelAllRequests()
            fadeoutAlarm.addRequest({ runAnimation(false) }, fadeoutDelay)
          }
        }
      }
    }

  fun display() {
    rootPane.add(this)
    runAnimation()
  }

  private fun isInsideMemePanel(e: MouseEvent): Boolean {
    val target = RelativePoint(e)
    val cmp = target.originalComponent
    return when {
      cmp.isShowing.not() -> true
      cmp is MenuElement -> false
      UIUtil.isDescendingFrom(cmp, this) -> true
      this.isShowing.not() -> false
      else -> {
        val point = target.screenPoint
        SwingUtilities.convertPointFromScreen(point, this)
        this.contains(point)
      }
    }
  }

  private fun createMemeContentPanel(meme: String): JBLabel {
    return JBLabel(meme)
  }

  private fun createMemeContentPanel(): JBLabel =
    createMemeContentPanel(
      """<html>
           <div style='margin: 5;'>
           <img src='${visualMeme.filePath}' alt='${visualMeme.imageAlt}' />
           </div>
         </html>
      """.trimIndent()
    )

  private fun positionMemePanel(settings: MemePanelSettings, width: Int, height: Int) {
    val (x, y) = getPosition(
      settings.anchor,
      rootPane.x + rootPane.width,
      rootPane.y + rootPane.height,
      Rectangle(width, height)
    )
    setLocation(x, y)
  }

  private fun clear() {
    alpha = CLEARED_ALPHA
    overlay = null
  }

  private fun getPosition(
    anchor: NotificationAnchor,
    parentWidth: Int,
    parentHeight: Int,
    memePanelBoundingBox: Rectangle,
  ): Pair<Int, Int> = when (anchor) {
    TOP_CENTER, CENTER, BOTTOM_CENTER ->
      (parentWidth - memePanelBoundingBox.width) / HALF_DIVISOR to when (anchor) {
        TOP_CENTER -> NOTIFICATION_Y_OFFSET
        BOTTOM_CENTER -> parentHeight - memePanelBoundingBox.height - NOTIFICATION_Y_OFFSET
        else -> (parentHeight - memePanelBoundingBox.height) / HALF_DIVISOR
      }
    else ->
      when (anchor) {
        TOP_LEFT,
        MIDDLE_LEFT,
        BOTTOM_LEFT -> NOTIFICATION_Y_OFFSET
        else -> parentWidth - memePanelBoundingBox.width
      } to when (anchor) {
        TOP_LEFT, TOP_RIGHT -> NOTIFICATION_Y_OFFSET
        BOTTOM_LEFT, BOTTOM_RIGHT ->
          parentHeight - memePanelBoundingBox.height - NOTIFICATION_Y_OFFSET
        else -> (parentHeight - memePanelBoundingBox.height) / HALF_DIVISOR
      }
  }

  override fun paintChildren(g: Graphics?) {
    if (overlay == null || alpha == CLEARED_ALPHA) {
      super.paintChildren(g)
    }
  }

  override fun paintComponent(g: Graphics?) {
    super.paintComponent(g)
    if (g !is Graphics2D) return

    if (overlay == null && alpha != CLEARED_ALPHA) {
      initComponentImage()
    }

    if (overlay != null && alpha != CLEARED_ALPHA) {
      g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)

      StartupUiUtil.drawImage(g, overlay!!, 0, 0, null)
    }
  }

  private fun initComponentImage() {
    if (overlay != null) return

    overlay = UIUtil.createImage(this, width, height, BufferedImage.TYPE_INT_ARGB)
    UIUtil.useSafely(overlay!!.graphics) { imageGraphics: Graphics2D ->
      fancyPaintChildren(imageGraphics)
    }
  }

  private fun fancyPaintChildren(imageGraphics2d: Graphics2D) {
    // Paint to an image without alpha to preserve fonts subpixel antialiasing
    val image: BufferedImage = ImageUtil.createImage(
      imageGraphics2d,
      width,
      height,
      BufferedImage.TYPE_INT_RGB
    )

    val fillColor = MessageType.INFO.popupBackground
    UIUtil.useSafely(image.createGraphics()) { imageGraphics: Graphics2D ->
      imageGraphics.paint = Color(fillColor.rgb) // create a copy to remove alpha
      imageGraphics.fillRect(0, 0, width, height)
      super.paintChildren(imageGraphics)
    }

    val g2d = imageGraphics2d.create() as Graphics2D

    try {
      if (JreHiDpiUtil.isJreHiDPI(g2d)) {
        val s = 1 / JBUIScale.sysScale(g2d)
        g2d.scale(s.toDouble(), s.toDouble())
      }
      StartupUiUtil.drawImage(g2d, makeColorTransparent(image, fillColor), 0, 0, null)
    } finally {
      g2d.dispose()
    }
  }

  private fun makeColorTransparent(image: Image, color: Color): Image {
    val markerRGB = color.rgb or -0x1000000
    return ImageUtil.filter(
      image,
      object : RGBImageFilter() {
        override fun filterRGB(x: Int, y: Int, rgb: Int): Int =
          if (rgb or -0x1000000 == markerRGB) {
            WHITE_HEX and rgb // set alpha to 0
          } else rgb
      }
    )
  }

  private fun runAnimation(runForwards: Boolean = true) {
    val self = this
    val animator = object : Animator(
      "Meme Machine",
      TOTAL_FRAMES,
      CYCLE_DURATION,
      false,
      runForwards
    ) {
      override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
        alpha = frame.toFloat() / totalFrames
        paintImmediately(0, 0, width, height)
      }

      override fun paintCycleEnd() {
        if (isForward) {
          clear()

          self.repaint()

          if (memePanelSettings.dismissal == TIMED) {
            setFadeOutTimer()
          }

          invulnerable = false
        } else {
          rootPane.remove(self)
          rootPane.revalidate()
          rootPane.repaint()
          Disposer.dispose(self)
        }
        Disposer.dispose(this)
      }

      private fun setFadeOutTimer() {
        self.fadeoutAlarm.addRequest(
          { self.runAnimation(false) },
          getMemeDuration(),
          null
        )
      }
    }

    animator.resume()
  }

  private fun getMemeDuration() =
    if (visualMeme.filePath.toString().endsWith(".gif", ignoreCase = true)) {
      val duration = GifService.getDuration(visualMeme.filePath)
      if (duration < MEME_DISPLAY_LIFETIME) {
        duration * (MEME_DISPLAY_LIFETIME / duration)
      } else {
        duration
      }
    } else {
      MEME_DISPLAY_LIFETIME
    }

  override fun dispose() {
    Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
  }
}
