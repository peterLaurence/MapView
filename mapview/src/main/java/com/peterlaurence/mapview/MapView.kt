package com.peterlaurence.mapview

import android.content.Context
import android.graphics.ColorFilter
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.peterlaurence.mapview.api.MinimumScaleMode
import com.peterlaurence.mapview.core.*
import com.peterlaurence.mapview.layout.GestureLayout
import com.peterlaurence.mapview.layout.setSize
import com.peterlaurence.mapview.markers.MarkerLayout
import com.peterlaurence.mapview.util.AngleDegree
import com.peterlaurence.mapview.util.toRad
import com.peterlaurence.mapview.view.TileCanvasView
import com.peterlaurence.mapview.viewmodel.TileCanvasViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.CoroutineContext

/**
 * The [MapView] is a subclass of [GestureLayout], specialized for displaying
 * [deepzoom](https://geoservices.ign.fr/documentation/geoservices/wmts.html) maps.
 *
 * Typical usage consists in 3 steps:
 * 1. Creation of the [MapView]
 * 2. Creation of a [TileStreamProvider]
 * 3. Configuration of the [MapView]
 *
 * Example:
 * ```
 * val mapView = MapView(context)
 * val tileStreamProvider = object : TileStreamProvider {
 *   override fun getTileStream(row: Int, col: Int, zoomLvl: Int): InputStream? {
 *     return FileInputStream(File("path_of_tile")) // or it can be a remote http fetch
 *   }
 * }
 *
 * val config = MapViewConfiguration(levelCount = 7, fullWidth = 25000, fullHeight = 12500,
 *   tileSize = 256, tileStreamProvider = tileStreamProvider).setMaxScale(2f)
 *
 * /* Configuration */
 * mapView.configure(config)
 * ```
 *
 * @author peterLaurence on 31/05/2019
 */
open class MapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        GestureLayout(context, attrs, defStyleAttr), CoroutineScope {

    private var visibleTilesResolver: VisibleTilesResolver? = null
    private var job = Job()

    private var tileSize: Int = 256
    private lateinit var tileCanvasView: TileCanvasView
    private lateinit var tileCanvasViewModel: TileCanvasViewModel
    lateinit var markerLayout: MarkerLayout
        private set
    lateinit var coordinateTranslater: CoordinateTranslater
        private set

    private lateinit var configuration: MapViewConfiguration

    private lateinit var throttledTask: SendChannel<Unit>
    private val refOwnerList = mutableListOf<ReferentialOwner>()
    private var savedState: SavedState? = null
    private var isConfigured = false
    private val viewport = Viewport()
    private var referentialData = ReferentialData()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    /**
     * Configure the [MapView], with a [MapViewConfiguration]. Other settings can be set using dedicated
     * public methods of the [MapView].
     *
     * There are two conventions when using [MapView].
     * 1. The provided [MapViewConfiguration.levelCount] will define the zoomLevels index that the provided
     * [MapViewConfiguration.tileStreamProvider] will be given for its [TileStreamProvider#zoomLevels].
     * The zoomLevels will be [0 ; [MapViewConfiguration.levelCount]-1].
     *
     * 2. A map is made of levels with level p+1 being twice bigger than level p.
     * The last level will be at scale 1. So all levels have scales between 0 and 1.
     *
     * So it is assumed that the scale of level 1 is twice the scale at level 0, and so on until
     * last level [MapViewConfiguration.levelCount] - 1 (which has scale 1).
     *
     * @param config the [MapViewConfiguration] which defines most of the parameters. A few others
     * require dedicated method call right after the configuration.
     */
    fun configure(config: MapViewConfiguration) {
        /* Safeguard - an existing instance should always be destroyed or we leak coroutines */
        if (isConfigured) throw IllegalStateException("This MapView instance is already configured, " +
                "either call destroy() on existing instance then re-create a MapView, or avoid a re-configure")

        /* Save the configuration */
        configuration = config

        /* Apply the configuration */
        setSize(config.fullWidth, config.fullHeight)
        val visibleTilesResolver = VisibleTilesResolver(config.levelCount, config.fullWidth, config.fullHeight,
                config.tileSize, magnifyingFactor = config.magnifyingFactor)
        this.visibleTilesResolver = visibleTilesResolver
        tileCanvasViewModel = TileCanvasViewModel(this, config.tileSize, visibleTilesResolver,
                config.tileStreamProvider, config.tileOptionsProvider, config.workerCount)
        this.tileSize = config.tileSize
        gestureController.rotationEnabled = config.rotationEnabled
        gestureController.handleRotationGesture = config.handleRotationGesture

        initChildViews(visibleTilesResolver)

        setScalePolicy(config)
        setStartScale(config.startScale)

        startInternals()
        isConfigured = true

        /* If we have a saved state (not null), it means we should restore it. It happens when the
         * configuration is done after the framework restores the state. Typically, it happens when
         * the MapView is added inside the parents's onCreateView() (as it should always be),
         * and the configuration is done later on (in onStart for example).
         */
        savedState?.let {
            restoreState(it)
        }

        /* Since various events that trigger a render (scale and layout change) may not happen right
         * after the configuration, ask for a first render explicitly.
         */
        renderVisibleTilesThrottled()
    }

    /**
     * Set the relative coordinates of the edges. It usually are projected values obtained from:
     * lat/lon --> projection --> X/Y (or Northing/Easting)
     * It can also be just latitude and longitude values, but it's the responsibility of the parent
     * hierarchy to provide lat/lon values in other public methods where relative coordinates are
     * expected (like when markers are added).
     *
     * @param left   The left edge of the rectangle used when calculating position
     * @param top    The top edge of the rectangle used when calculating position
     * @param right  The right edge of the rectangle used when calculating position
     * @param bottom The bottom edge of the rectangle used when calculating position
     */
    fun defineBounds(left: Double, top: Double, right: Double, bottom: Double) {
        val width = configuration.fullWidth
        val height = configuration.fullHeight
        coordinateTranslater = CoordinateTranslater(width, height, left, top, right, bottom)
    }

    fun addReferentialOwner(referentialOwner: ReferentialOwner) {
        referentialOwner.referentialData = referentialData
        refOwnerList.add(referentialOwner)
    }

    fun removeReferentialOwner(referentialOwner: ReferentialOwner) {
        refOwnerList.remove(referentialOwner)
    }

    /**
     * Stop everything.
     * [MapView] then does necessary housekeeping. After this call, the [MapView] should be removed
     * from all view trees.
     */
    fun destroy() {
        refOwnerList.clear()
        job.cancel()
    }

    private fun initChildViews(visibleTilesResolver: VisibleTilesResolver) {
        /* Remove the TileCanvasView if it was already added */
        if (this::tileCanvasView.isInitialized) {
            removeView(tileCanvasView)
        }
        tileCanvasView = TileCanvasView(context, tileCanvasViewModel, tileSize, visibleTilesResolver)
        addView(tileCanvasView, 0)

        if (!this::markerLayout.isInitialized) {
            markerLayout = MarkerLayout(context)
            addView(markerLayout)
        }
    }

    private fun setStartScale(startScale: Float?) {
        gestureController.scale = startScale ?: (visibleTilesResolver?.getScaleForLevel(0) ?: 1f)
    }

    private fun setScalePolicy(config: MapViewConfiguration) {
        /* Don't allow MinimumScaleMode.NONE when no min scale is set */
        if (config.minimumScaleMode == MinimumScaleMode.NONE) {
            config.minScale?.let {
                if (it == 0f) error("Min scale must be greater than 0 when using MinimumScaleMode.NONE")
                gestureController.setMinimumScaleMode(config.minimumScaleMode)
                gestureController.setScaleLimits(it, config.maxScale)
            } ?: error("A min scale greater than 0 must be set when using MinimumScaleMode.NONE")
        } else {
            gestureController.setMinimumScaleMode(config.minimumScaleMode)
            gestureController.setScaleLimits(config.minScale ?: 0f, config.maxScale)
        }
    }

    private fun renderVisibleTilesThrottled() {
        if (this::throttledTask.isInitialized) {
            throttledTask.offer(Unit)
        }
    }

    private fun startInternals() {
        throttledTask = throttle(wait = 18) {
            val viewport = updateViewport()
            tileCanvasViewModel.setViewport(viewport)
        }
    }

    private fun updateViewport(): Viewport {
        val padding = configuration.padding
        return viewport.apply {
            left = scrollX - padding - offsetX
            top = scrollY - padding - offsetY
            right = left + width + padding * 2
            bottom = top + height + padding * 2
            angleRad = referentialData.angle.toRad()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        if (changed) {
            renderVisibleTilesThrottled()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)

        renderVisibleTilesThrottled()
    }

    override fun onScaleChanged(currentScale: Float, previousScale: Float) {
        visibleTilesResolver?.setScale(currentScale) ?: return

        renderVisibleTilesThrottled()
    }

    /**
     * The [ReferentialData] is updated on any of those property change:
     * * scale
     * * angle
     * * scroll
     * Any of those properties change requires an update of the [MapView] and its child views.
     * Also, [ReferentialOwner]s registered as listeners of [ReferentialData] are notified as well.
     */
    override fun onReferentialChanged(angle: AngleDegree, scale: Float, centerX: Double, centerY: Double) {
        /* Update our own reference of ReferentialData */
        referentialData.apply {
            this.angle = angle
            this.scale = scale
            this.centerX = centerX
            this.centerY = centerY
        }
        updateAllRefOwners()
        renderVisibleTilesThrottled()
    }

    private fun updateAllRefOwners() {
        if (this::tileCanvasView.isInitialized) {
            tileCanvasView.referentialData = this.referentialData
        }
        if (this::markerLayout.isInitialized) {
            markerLayout.referentialData = this.referentialData
        }

        refOwnerList.forEach {
            it.referentialData = this.referentialData
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        markerLayout.removeAllCallout()
        return super.onTouchEvent(event)
    }

    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        val x = scrollX + event.x.toInt() - offsetX
        val y = scrollY + event.y.toInt() - offsetY
        markerLayout.processHit(x, y)
        return super.onSingleTapConfirmed(event)
    }

    override fun onSaveInstanceState(): Parcelable? {
        val parentState = super.onSaveInstanceState() ?: Bundle()
        return SavedState(parentState, scale, centerX = scrollX + halfWidth,
                centerY = scrollY + halfHeight, referentialData = referentialData)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState(state)

        savedState = state as? SavedState

        /* If the configuration of MapView hasn't been done yet (because the user might configure it
         * after the MapView is added to the view hierarchy), don't try to restore the state now.
         * It will be restored at the end of the configuration using the saved state.
         */
        if (isConfigured) {
            val savedState = savedState ?: return
            restoreState(savedState)
        }
    }

    private fun restoreState(savedState: SavedState) {
        gestureController.scale = savedState.scale
        gestureController.angle = savedState.referentialData.angle
        referentialData = savedState.referentialData

        post {
            scrollToAndCenter(savedState.centerX, savedState.centerY)
        }
    }
}

@Parcelize
data class ReferentialData(var rotationEnabled: Boolean = true,
                           var angle: AngleDegree = 0f,
                           var scale: Float = 0f,
                           var centerX: Double = 0.0,
                           var centerY: Double = 0.0) : Parcelable

/**
 * The set of parameters of the [MapView]. Some of them are mandatory:
 * [levelCount], [fullWidth], [fullHeight], [tileSize], [tileStreamProvider].
 *
 * @param levelCount the number of levels
 * @param fullWidth the width of the map in pixels at scale 1
 * @param fullHeight the height of the map in pixels at scale 1
 * @param tileSize the size of tiles (must be squares)
 * @param tileStreamProvider the tiles provider
 */
data class MapViewConfiguration(val levelCount: Int, val fullWidth: Int, val fullHeight: Int,
                                val tileSize: Int, val tileStreamProvider: TileStreamProvider) {
    /**
     * The maximum level of parallelism. For local tiles, a value of the number of cores minus one
     * is enough.
     * For remote HTTP tiles, don't hesitate to raise this up to 60 (if the device is powerful enough).
     */
    var workerCount = Runtime.getRuntime().availableProcessors() - 1
        private set

    var minScale: Float? = null
        private set

    var maxScale = 1f
        private set

    var startScale: Float? = null
        private set

    var magnifyingFactor: Int = 0
        private set

    var padding: Int = 0
        private set

    var minimumScaleMode: MinimumScaleMode = MinimumScaleMode.FIT
        private set

    var rotationEnabled: Boolean = false
        private set

    var handleRotationGesture: Boolean = true
        private set

    var tileOptionsProvider: TileOptionsProvider? = null
        private set

    /**
     * Define the size of the thread pool that will handle tile decoding. In some situations, a pool
     * of several times the numbers of cores is suitable. Whereas sometimes we want to limit to just
     * 1, as some tile providers forbid multi threading to limit the load of the remote servers.
     */
    fun setWorkerCount(n: Int): MapViewConfiguration {
        if (n > 0) workerCount = n
        return this
    }

    /**
     * Set the minimum scale. It must be at least 0, except when using [MinimumScaleMode.NONE] where the
     * min scale must be greater than 0.
     */
    fun setMinScale(scale: Float): MapViewConfiguration {
        if (scale < 0f) error("Minimum scale must be at least 0")
        minScale = scale
        return this
    }

    fun setMaxScale(scale: Float): MapViewConfiguration {
        maxScale = scale
        return this
    }

    /**
     * Set the start scale of the [MapView]. Note that it will be constrained by the
     * [MinimumScaleMode]. By default, the minimum scale mode is
     * [MinimumScaleMode.FIT] and on startup, the [MapView] will try to set the scale
     * which corresponds to the level 0, but constrained with the minimum scale mode. So by default,
     * the startup scale can be also the minimum scale.
     */
    fun setStartScale(scale: Float): MapViewConfiguration {
        startScale = scale
        return this
    }

    /**
     * Alter the level at which tiles are picked for a given scale. By default,
     * the level immediately higher (in index) is picked, to avoid sub-sampling. This corresponds to a
     * [magnifyingFactor] of 0. The value 1 will result in picking the current level at a given scale,
     * which will be at a relative scale between 1.0 and 2.0
     */
    fun setMagnifyingFactor(factor: Int): MapViewConfiguration {
        magnifyingFactor = factor
        return this
    }

    /**
     * The visible viewport will be considered larger in all directions by the provided [pixels]
     * count. A recommended value of twice the tile size results in a seamless single image experience.
     */
    fun setPadding(pixels: Int): MapViewConfiguration {
        padding = pixels
        return this
    }

    /**
     * Set the minimum scale mode. See [MinimumScaleMode].
     */
    fun setMinimumScaleMode(scaleMode: MinimumScaleMode): MapViewConfiguration {
        minimumScaleMode = scaleMode
        return this
    }

    /**
     * Enables rotating the map, either with gestures (the default), or via APIs.
     * @param handleRotationGesture Whether or not rotation gesture should be handled by the MapView.
     * Default is `true`.
     */
    fun enableRotation(handleRotationGesture: Boolean = true): MapViewConfiguration {
        rotationEnabled = true
        this.handleRotationGesture = handleRotationGesture
        return this
    }

    /**
     * Sets the additional options provider for rendering tiles. Default is `null`.
     */
    fun setTileOptionsProvider(tileOptionsProvider: TileOptionsProvider) : MapViewConfiguration {
        this.tileOptionsProvider = tileOptionsProvider
        return this
    }
}

@Parcelize
internal data class SavedState(val parcelable: Parcelable, val scale: Float, val centerX: Int, val centerY: Int,
                               val referentialData: ReferentialData) : View.BaseSavedState(parcelable)

interface ReferentialOwner {
    var referentialData: ReferentialData
}
