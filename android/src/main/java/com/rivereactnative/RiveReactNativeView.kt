package com.rivereactnative

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.RiveViewLifecycleObserver
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.*
import app.rive.runtime.kotlin.core.errors.*
import app.rive.runtime.kotlin.renderers.PointerEvents
import com.android.volley.NetworkResponse
import com.android.volley.ParseError
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.Volley
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.ExceptionsManagerModule
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL


class ReactNativeRiveViewLifecycleObserver(dependencies: MutableList<RefCount>) :
  RiveViewLifecycleObserver(dependencies) {
  /**
   * OnDestroy is different in Rive ReactNative compared to Rive Android.
   * Releasing dependencies is managed in `ReactNativeRiveAnimationView.dispose()`
   */
  @SuppressLint("MissingSuperCall")
  override fun onDestroy(owner: LifecycleOwner) {
    owner.lifecycle.removeObserver(this)
  }

  fun dispose() {
    dependencies.forEach { it.release() }
    dependencies.clear()
  }
}

@SuppressLint("ViewConstructor")
class ReactNativeRiveAnimationView(private val context: ThemedReactContext) :
  RiveAnimationView(context) {

  fun dispose() {
    (lifecycleObserver as ReactNativeRiveViewLifecycleObserver).dispose();
  }

  @SuppressLint("VisibleForTests")
  override fun createObserver(): LifecycleObserver {
    return ReactNativeRiveViewLifecycleObserver(
      listOfNotNull(
        controller, rendererAttributes.assetLoader
      ).toMutableList()
    )
  }
}

@SuppressLint("ViewConstructor")
class RiveReactNativeView(private val context: ThemedReactContext) : FrameLayout(context) {
  private var riveAnimationView: ReactNativeRiveAnimationView? = null
  private var resId: Int = -1
  private var url: String? = null
  private var animationName: String? = null
  private var stateMachineName: String? = null
  private var artboardName: String? = null
  private var fit: Fit = Fit.CONTAIN
  private var layoutScaleFactor: Float? = null
  private var alignment: Alignment = Alignment.CENTER
  private var autoplay: Boolean = false
  private var assetsHandled: ReadableMap? = null
  private var shouldBeReloaded = true
  private var exceptionManager: ExceptionsManagerModule? = null
  private var isUserHandlingErrors = false
  private var willDispose = false
  private var listener: RiveFileController.Listener
  private var eventListener: RiveFileController.RiveEventListener

  enum class Events(private val mName: String) {
    PLAY("onPlay"), PAUSE("onPause"), STOP("onStop"), LOOP_END("onLoopEnd"), STATE_CHANGED("onStateChanged"), RIVE_EVENT(
      "onRiveEventReceived"
    ),
    ERROR("onError");

    override fun toString(): String {
      return mName
    }
  }

  init {
    riveAnimationView = ReactNativeRiveAnimationView(context)

    listener = object : RiveFileController.Listener {
      override fun notifyLoop(animation: PlayableInstance) {
        if (animation is LinearAnimationInstance) {
          onLoopEnd(animation.name, RNLoopMode.mapToRNLoopMode(animation.loop))
        } else {
          throw IllegalArgumentException("Only animation can be passed as an argument")
        }
      }

      override fun notifyPause(animation: PlayableInstance) {
        if (animation is LinearAnimationInstance) {
          onPause(animation.name)
        }
        if (animation is StateMachineInstance) {
          onPause(animation.name, true)
        }
      }

      override fun notifyPlay(animation: PlayableInstance) {
        if (animation is LinearAnimationInstance) {
          onPlay(animation.name)
        }
        if (animation is StateMachineInstance) {
          onPlay(animation.name, true)
        }
      }

      override fun notifyStateChanged(stateMachineName: String, stateName: String) {
        onStateChanged(stateMachineName, stateName)
      }

      override fun notifyStop(animation: PlayableInstance) {
        if (animation is LinearAnimationInstance) {
          onStop(animation.name)
        }
        if (animation is StateMachineInstance) {
          onStop(animation.name, true)
        }
      }

    }

    eventListener = object : RiveFileController.RiveEventListener {
      override fun notifyEvent(event: RiveEvent) {
        when (event) {
          is RiveGeneralEvent -> onRiveEventReceived(event)
          is RiveOpenURLEvent -> onRiveEventReceived(event)
        }
      }
    }

    addListeners()
    autoplay = false
    addView(riveAnimationView)
  }

  fun dispose() {
    willDispose = true
  }

  override fun onDetachedFromWindow() {
    if (willDispose) {
      riveAnimationView?.dispose()
      removeListeners()
      clearReferences()
    }

    super.onDetachedFromWindow()
  }

  private fun addListeners() {
    riveAnimationView?.registerListener(listener)
    riveAnimationView?.addEventListener(eventListener)
  }

  private fun removeListeners() {
    riveAnimationView?.unregisterListener(listener)
    riveAnimationView?.removeEventListener(eventListener)
  }

  private fun clearReferences() {
    riveAnimationView = null
    exceptionManager = null
    assetsHandled = null
  }

  fun onPlay(animationName: String, isStateMachine: Boolean = false) {
    val reactContext = context as ReactContext

    val data = Arguments.createMap()
    data.putString("animationName", animationName)
    data.putBoolean("isStateMachine", isStateMachine)

    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.PLAY.toString(), data)
  }

  fun onPause(animationName: String, isStateMachine: Boolean = false) {
    val reactContext = context as ReactContext

    val data = Arguments.createMap()
    data.putString("animationName", animationName)
    data.putBoolean("isStateMachine", isStateMachine)

    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.PAUSE.toString(), data)
  }

  fun onStop(animationName: String, isStateMachine: Boolean = false) {
    val reactContext = context as ReactContext

    val data = Arguments.createMap()
    data.putString("animationName", animationName)
    data.putBoolean("isStateMachine", isStateMachine)

    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.STOP.toString(), data)
  }

  fun onLoopEnd(animationName: String, loopMode: RNLoopMode) {
    val reactContext = context as ReactContext

    val data = Arguments.createMap()
    data.putString("animationName", animationName)
    data.putString("loopMode", loopMode.toString())

    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.LOOP_END.toString(), data)
  }

  fun onStateChanged(stateMachineName: String, stateName: String) {
    val reactContext = context as ReactContext
    val data = Arguments.createMap()
    data.putString("stateMachineName", stateMachineName)
    data.putString("stateName", stateName)

    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.STATE_CHANGED.toString(), data)
  }

  private fun convertHashMapToWritableMap(hashMap: HashMap<String, Any>): WritableMap {
    val writableMap = Arguments.createMap()

    for ((key, value) in hashMap) {
      when (value) {
        is String -> writableMap.putString(key, value)
        is Int -> writableMap.putInt(key, value)
        is Float -> writableMap.putDouble(key, value.toDouble())
        is Double -> writableMap.putDouble(key, value)
        is Boolean -> writableMap.putBoolean(key, value)
      }
    }

    return writableMap
  }

  fun onRiveEventReceived(event: RiveEvent) {
    val reactContext = context as ReactContext
    val topLevelDict = Arguments.createMap()

    val eventProperties = Arguments.createMap().apply {
      putString("name", event.name)
      putDouble("delay", event.delay.toDouble())
      putMap("properties", convertHashMapToWritableMap(event.properties))
    }

    if (event is RiveOpenURLEvent) {
      eventProperties.putString("url", event.url)
      eventProperties.putString("target", event.target)
    }

    topLevelDict.putMap(
      "riveEvent", eventProperties
    )

    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.RIVE_EVENT.toString(), topLevelDict)
  }

  fun play(
    animationName: String, rnLoopMode: RNLoopMode, rnDirection: RNDirection, isStateMachine: Boolean
  ) {
    val loop = RNLoopMode.mapToRiveLoop(rnLoopMode)
    val direction = RNDirection.mapToRiveDirection(rnDirection)
    if (animationName.isEmpty()) {
      riveAnimationView?.play(
        loop, direction
      ) // intentionally we skipped areStateMachines argument to keep same behaviour as it is in the native sdk
    } else {
      try {
        riveAnimationView?.play(animationName, loop, direction, isStateMachine)
      } catch (ex: RiveException) {
        handleRiveException(ex)
      }
    }
  }

  fun pause() {
    try {
      if (riveAnimationView?.playingAnimations?.isNotEmpty() == true) {
        riveAnimationView!!.pause(riveAnimationView!!.playingAnimations.first().name)
      } else if (riveAnimationView?.playingStateMachines?.isNotEmpty() == true) {
        riveAnimationView!!.pause(riveAnimationView!!.playingStateMachines.first().name, true)
      } else {
        riveAnimationView?.pause()
      }
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun stop() {
    try {
      riveAnimationView?.stop()
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun reset() {
    url?.let {
      if (resId == -1) {
        riveAnimationView?.artboardRenderer?.reset()
      }
    } ?: run {
      if (resId != -1) {
        riveAnimationView?.reset()
      }
    }
  }

  fun touchBegan(x: Float, y: Float) {
    riveAnimationView?.controller?.pointerEvent(PointerEvents.POINTER_DOWN, x, y)
  }

  fun touchEnded(x: Float, y: Float) {
    riveAnimationView?.controller?.pointerEvent(PointerEvents.POINTER_UP, x, y)
  }

  fun setTextRunValue(textRunName: String, textValue: String) {
    try {
      riveAnimationView?.controller?.activeArtboard?.textRun(textRunName)?.text = textValue;
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun setTextRunValueAtPath(textRunName: String, textValue: String, path: String) {
    try {
      riveAnimationView?.controller?.activeArtboard?.textRun(textRunName, path)?.text = textValue
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun update() {
    reloadIfNeeded()
  }

  fun setResourceName(resourceName: String?) {
    resourceName?.let {
      resId = resources.getIdentifier(resourceName, "raw", context.packageName)
      if (resId == 0) {
        resId = -1
      }
    } ?: run {
      resId = -1
    }

    shouldBeReloaded = true
  }

  fun setFit(rnFit: RNFit) {
    val riveFit = RNFit.mapToRiveFit(rnFit)
    this.fit = riveFit
    riveAnimationView?.fit = riveFit
  }

  fun setLayoutScaleFactor(layoutScaleFactor: Float?) {
    this.layoutScaleFactor = layoutScaleFactor
    riveAnimationView?.layoutScaleFactor = layoutScaleFactor
  }

  fun setAlignment(rnAlignment: RNAlignment) {
    val riveAlignment = RNAlignment.mapToRiveAlignment(rnAlignment)
    this.alignment = riveAlignment
    riveAnimationView?.alignment = riveAlignment
  }

  fun setAutoplay(autoplay: Boolean) {
    this.autoplay = autoplay
    shouldBeReloaded = true
  }

  fun setUrl(url: String?) {
    this.url = url
    shouldBeReloaded = true
  }


  private fun handleSourceAssetId(source: String, asset: FileAsset) {
    val scheme = runCatching { Uri.parse(source).scheme }.getOrNull()

    // Handle dev mode (URL instead of asset id)
    if (scheme != null) {
      handleSourceUrl(source, asset)
      return;
    }

    // Handle release mode (URL instead of asset id)
    // Resource needs to be loaded in release mode
    // https://github.com/facebook/react-native/issues/24963#issuecomment-532168307
    val resourceId = getResourceId(source)

    var errorMessage: String? = null
    if (resourceId != 0) {
      try {
        resources.openRawResource(resourceId).use {
          val bytes = it.readBytes()
          processAssetBytes(bytes, asset)
        }
      } catch (e: IOException) {
        errorMessage = "IO Exception while reading resource: $source"
      } catch (e: Resources.NotFoundException) {
        errorMessage = "Resource not found: $source"
      } catch (e: Exception) {
        errorMessage = "Unexpected error while processing resource: $source"
      }
    } else {
      errorMessage = "Resource not found: $source"
    }

    errorMessage?.let {
      if (isUserHandlingErrors) {
        val rnRiveError = RNRiveError.FileNotFound
        rnRiveError.message = errorMessage
        sendErrorToRN(rnRiveError)
      } else {
        throw IllegalStateException(errorMessage)
      }
    }
  }

  private fun handleSourceUrl(source: String, asset: FileAsset) {
    downloadUrlAsset(source) { bytes -> processAssetBytes(bytes, asset) }
  }

  private fun handleSourceAsset(fileName: String, path: String?, asset: FileAsset) {
    val fullPath = if (path == null) fileName else constructFilePath(fileName, path)
    val assetBytes = readAssetBytes(context, fullPath)
    assetBytes?.let {
      processAssetBytes(it, asset)
    }
  }

  private fun loadAsset(source: ReadableMap, asset: FileAsset) {
    val sourceAssetId = source.getString("sourceAssetId")
    val sourceUrl = source.getString("sourceUrl")
    val sourceAsset = source.getString("sourceAsset")

    when {
      sourceAssetId != null -> handleSourceAssetId(sourceAssetId, asset)
      sourceUrl != null -> handleSourceUrl(sourceUrl, asset)
      sourceAsset != null -> handleSourceAsset(sourceAsset, source.getString("path"), asset)
    }
  }

  private fun reloadIfNeeded() {
    if (shouldBeReloaded) {
      val assetStore = assetsHandled?.let {
        RiveReactNativeAssetStore(
          it, loadAssetHandler = ::loadAsset
        )
      }
      if (assetStore != null) {
        riveAnimationView?.setAssetLoader(assetStore);
      }

      url?.let {
        if (resId == -1) {
          setUrlRiveResource(it)
        } else {
          throw IllegalStateException("You cannot pass both resourceName and url at the same time")
        }
      } ?: run {
        if (resId != -1) {
          try {
            riveAnimationView?.setRiveResource(
              resId,
              fit = this.fit,
              alignment = this.alignment,
              autoplay = this.autoplay,
              stateMachineName = this.stateMachineName,
              animationName = this.animationName,
              artboardName = this.artboardName
            )
            url = null
          } catch (ex: RiveException) {
            handleRiveException(ex)
          }

        } else {
          handleFileNotFound()
        }
      }
      shouldBeReloaded = false
    }
  }


  private fun setUrlRiveResource(url: String, autoplay: Boolean = this.autoplay) {
    downloadUrlAsset(url) { bytes ->
      try {
        riveAnimationView?.setRiveBytes(
          bytes,
          fit = this.fit,
          alignment = this.alignment,
          autoplay = autoplay,
          stateMachineName = this.stateMachineName,
          animationName = this.animationName,
          artboardName = this.artboardName
        )
      } catch (ex: RiveException) {
        handleRiveException(ex)
      }
    }
  }

  fun setArtboardName(artboardName: String) {
    try {
      this.artboardName = artboardName
      riveAnimationView?.artboardName = artboardName // it causes reloading
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun setAnimationName(animationName: String) {
    this.animationName = animationName
    shouldBeReloaded = true
  }

  fun setAssetsHandled(assetsHandled: ReadableMap?) {
    this.assetsHandled = assetsHandled;
    shouldBeReloaded = true;
  }

  fun setStateMachineName(stateMachineName: String) {
    this.stateMachineName = stateMachineName
    shouldBeReloaded = true
  }

  fun setIsUserHandlingErrors(isUserHandlingErrors: Boolean) {
    this.isUserHandlingErrors = isUserHandlingErrors
  }

  fun fireState(stateMachineName: String, inputName: String) {
    try {
      riveAnimationView?.fireState(stateMachineName, inputName)
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun setBooleanState(stateMachineName: String, inputName: String, value: Boolean) {
    try {
      riveAnimationView?.setBooleanState(stateMachineName, inputName, value)
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun setNumberState(stateMachineName: String, inputName: String, value: Float) {
    try {
      riveAnimationView?.setNumberState(stateMachineName, inputName, value)
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun fireStateAtPath(inputName: String, path: String) {
    try {
      riveAnimationView?.fireStateAtPath(inputName, path)
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun setBooleanStateAtPath(inputName: String, value: Boolean, path: String) {
    try {
      riveAnimationView?.setBooleanStateAtPath(inputName, value, path)
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  fun setNumberStateAtPath(inputName: String, value: Float, path: String) {
    try {
      riveAnimationView?.setNumberStateAtPath(inputName, value, path)
    } catch (ex: RiveException) {
      handleRiveException(ex)
    }
  }

  private fun handleRiveException(exception: RiveException) {
    if (isUserHandlingErrors) {
      val rnRiveError = RNRiveError.mapToRNRiveError(exception)
      rnRiveError?.let {
        sendErrorToRN(rnRiveError)
      }
    } else {
      showRNRiveError("${exception.message}", exception)
    }
  }

  private fun handleFileNotFound() {
    if (isUserHandlingErrors) {
      val rnRiveError = RNRiveError.FileNotFound
      rnRiveError.message = "File resource not found. You must provide correct url or resourceName!"
      sendErrorToRN(rnRiveError)
    } else {
      throw IllegalStateException("File resource not found. You must provide correct url or resourceName!")
    }
  }

  /**
   * Function to read an asset file's bytes
   *
   * @param context The context used to access assets
   * @param fileName The name of the asset file
   * @return A ByteArray containing the file's bytes, or null if an error occurs
   */
  private fun readAssetBytes(context: Context, fileName: String): ByteArray? {
    val assetManager = context.assets
    var inputStream: InputStream? = null
    return try {
      inputStream = assetManager.open(fileName)
      val bytes = inputStream.readBytes()
      bytes
    } catch (e: IOException) {
      if (isUserHandlingErrors) {
        val rnRiveError = RNRiveError.IncorrectRiveFileUrl
        rnRiveError.message = "Unable to read file from assets: $fileName"
        sendErrorToRN(rnRiveError)
      } else {
        showRNRiveError("Unable to read file from assets $fileName", e)
      }
      null
    } finally {
      inputStream?.close()
    }
  }

  private fun downloadUrlAsset(url: String, listener: Response.Listener<ByteArray>) {
    if (!isValidUrl(url)) {
      handleInvalidUrlError(url)
      return
    }

    val queue = Volley.newRequestQueue(context)

    val stringRequest = RNRiveFileRequest(
      url, listener
    ) { error -> handleURLAssetError(url, error, isUserHandlingErrors) }

    queue.add(stringRequest)
  }

  private fun processAssetBytes(bytes: ByteArray, asset: FileAsset) {
    when (asset) {
      is ImageAsset -> asset.image = RiveRenderImage.make(bytes)
      is FontAsset -> asset.font = RiveFont.make(bytes)
      is AudioAsset -> asset.audio = RiveAudio.make(bytes)
    }
  }

  private fun handleURLAssetError(
    source: String, error: VolleyError, isUserHandlingErrors: Boolean
  ) {
    if (error.networkResponse?.statusCode == 404) {
      if (isUserHandlingErrors) {
        val rnRiveError = RNRiveError.IncorrectRiveFileUrl
        rnRiveError.message = "Bad URL: $source"
        sendErrorToRN(rnRiveError)
      } else {
        showRNRiveError("Bad URL: $source", error)
      }
    } else {
      if (isUserHandlingErrors) {
        val rnRiveError = RNRiveError.IncorrectRiveFileUrl
        rnRiveError.message = "Unable to download the Rive asset file from: $source"
        sendErrorToRN(rnRiveError)
      } else {
        showRNRiveError("Unable to download Rive asset file $source", error)
      }
    }
  }

  private fun handleInvalidUrlError(source: String) {
    if (isUserHandlingErrors) {
      val rnRiveError = RNRiveError.IncorrectRiveFileUrl
      rnRiveError.message = "Invalid URL: $source"
      sendErrorToRN(rnRiveError)
    } else {
      showRNRiveError("Invalid URL: $source", null)
    }
  }

  private fun isValidUrl(url: String): Boolean {
    return try {
      URL(url)
      true
    } catch (e: MalformedURLException) {
      false
    }
  }

  private fun constructFilePath(filename: String, path: String): String {
    return if (path.endsWith("/")) "$path$filename" else "$path/$filename"
  }

  private fun getResourceId(source: String): Int {
    val resourceTypes = listOf("raw", "drawable")

    for (type in resourceTypes) {
      val resourceId = context.resources.getIdentifier(source, type, context.packageName)
      if (resourceId != 0) {
        return resourceId
      }
    }

    return 0
  }


  private fun sendErrorToRN(error: RNRiveError) {
    val reactContext = context as ReactContext
    val data = Arguments.createMap()
    data.putString("type", error.toString())
    data.putString("message", error.message)
    reactContext.getJSModule(RCTEventEmitter::class.java)
      .receiveEvent(id, Events.ERROR.toString(), data)
  }

  private fun showRNRiveError(message: String, error: Throwable?) {
    val errorMap = Arguments.createMap()
    errorMap.putString("message", message)
    if (error != null) {
      errorMap.putArray("stack", createStackTraceForRN(error.stackTrace))
    }
    exceptionManager?.reportException(errorMap)
  }

  private fun createStackTraceForRN(stackTrace: Array<StackTraceElement>): ReadableArray {
    val stackTraceReadableArray = Arguments.createArray()
    for (stackTraceElement in stackTrace) {
      val stackTraceElementMap = Arguments.createMap()
      stackTraceElementMap.putString("methodName", stackTraceElement.methodName)
      stackTraceElementMap.putInt("lineNumber", stackTraceElement.lineNumber)
      stackTraceElementMap.putString("file", stackTraceElement.fileName)

      stackTraceReadableArray.pushMap(stackTraceElementMap)
    }
    return stackTraceReadableArray
  }
}

typealias LoadAssetHandler = (source: ReadableMap, asset: FileAsset) -> Unit

private class RiveReactNativeAssetStore(
  private val assetsHandled: ReadableMap, private val loadAssetHandler: LoadAssetHandler
) : FileAssetLoader() {
  override fun loadContents(asset: FileAsset, inBandBytes: ByteArray): Boolean {
    val assetData = assetsHandled.getMap(asset.uniqueFilename.substringBeforeLast("."))

    val source = assetData?.getMap("source") ?: return false // Do not handle the asset.

    CoroutineScope(Dispatchers.IO).launch {
      loadAssetHandler(source, asset)
    }
    return true // user supplied asset, attempt to load
  }
}

class RNRiveFileRequest(
  url: String,
  private val listener: Response.Listener<ByteArray>,
  errorListener: Response.ErrorListener
) : Request<ByteArray>(Method.GET, url, errorListener) {

  override fun deliverResponse(response: ByteArray) = listener.onResponse(response)

  override fun parseNetworkResponse(response: NetworkResponse?): Response<ByteArray> {
    return try {
      val bytes = response?.data ?: ByteArray(0)
      Response.success(bytes, HttpHeaderParser.parseCacheHeaders(response))
    } catch (e: UnsupportedEncodingException) {
      Response.error(ParseError(e))
    }
  }
}

