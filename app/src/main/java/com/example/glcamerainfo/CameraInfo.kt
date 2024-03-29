package com.example.glcamerainfo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata.*
import android.os.Build
import android.os.Handler
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class CameraInfo(private val activity: Activity, private val cameraViewModel: CameraViewModel) : CoroutineScope {

    /** Run all co-routines on Main  */
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job


    /** An additional thread for running tasks that shouldn't block the UI */
    // private var mBackgroundThread: HandlerThread? = null

    /** A [Handler] for running tasks in the background */
    // private var mBackgroundHandler: Handler? = null

    /** A [CameraManager] for, well, managing the camera! */
    private var mCameraManager: CameraManager? = null

    /** [CameraManager] will be used to get [CameraCharacteristics] */
    private var mCameraCharacteristics: CameraCharacteristics? = null

    /** ID of the current [CameraDevice] */
    private var mCameraId: String? = null

    /** A reference to the opened [CameraDevice] In this app, this will always be the rear-facing camera. */
    private var mCameraDevice: CameraDevice? = null

    /** A [Semaphore] to prevent the app from exiting before closing the camera */
    private val mCameraOpenCloseLock = Semaphore(1)

    private var mFocusCharacteristics: IntArray? = null

    /** Range of supported ISO settings */
    private var mRangeISO: Range<Int>? = null

    /** Range of supported Exposure Value settings */
    private var mRangeExposure: Range<Long>? = null

    /** Range of supported Exposure Compensation values */
    private var mRangeCompensation: Range<Int>? = null

    /**
     * This [CameraDevice.StateCallback] is called when the [CameraDevice] changes it's state
     */
    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {

            mCameraOpenCloseLock.release()
            mCameraDevice = cameraDevice

            mFocusCharacteristics = getFocusCharacteristics()

            // get a list of supported ISO settings and update the CameraFragment view model
            mRangeISO = getSupportedISOValues()
            cameraViewModel.isoValues?.value = mRangeISO

            // get a list of supported Exposure settings and update the CameraFragment view model
            mRangeExposure = getSupportedExposureValues()
            cameraViewModel.exposureValues?.value = mRangeExposure

            // get a list of supported EV Compensation values and update the CameraFragment view model
            mRangeCompensation = getSupportedCompensationValues()
            cameraViewModel.evCompValues?.value = mRangeCompensation
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            Timber.e("onError() called")
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }
    }

    /**
     * Opens the camera
     * This never gets called unless we already have permissions.
     * We check again to satisfy Android requirements...
     */
    fun openCamera() {
        Timber.d("openCamera() called...")

        if (mCameraDevice != null) return

        mCameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mCameraId = getRearFacingCamera()

        try {
            // Wait for camera to open - 2.5 seconds is "sufficient"
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            // check yet again for correct permissions and then OPEN the camera
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                mCameraId?.also {
                    this.launch(this.coroutineContext) {
                        mCameraManager?.openCamera(it, mStateCallback, null)
                    }
                }
            }
        } catch (e: CameraAccessException) {
            Timber.e(e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    fun closeCamera() {
        Timber.d("closeCamera() called...")

        try {
            mCameraOpenCloseLock.acquire()
            mCameraDevice?.close()
            mCameraDevice = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    /**
     * Get a [Range] of supported camera ISO values. The range may be NULL if the device
     * does not support manual ISO settings.
     * @return A [Range] of supported ISO values as [Integer]s
     */
    private fun getSupportedISOValues(): Range<Int>? {

        return mCameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    }

    /**
     * Get a [Range] of supported camera Exposure values. The range may be NULL if the device
     * does not support manual Exposure settings.
     * @return A [Range] of supported Exposure values as [Long]s
     */
    private fun getSupportedExposureValues(): Range<Long>? {

        return mCameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    }

    /**
     * Get a [Range] of supported camera Exposure Compensation values. The range may be NULL if the device
     * does not support Exposure Compensation settings.
     * @return A [Range] of supported Exposure Compensation values as [Int]s
     */
    private fun getSupportedCompensationValues(): Range<Int>? {

        return mCameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    }

    /**
     * Returns a list of AutoFocus modes for the device's camera
     * @return An [Array] of [Integer] values
     */
    private fun getFocusCharacteristics(): IntArray? {
        return mCameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    }

    /**
     * Returns the Focus Capability of the device's camera
     * @return A [String] containing the Focus Capabilities of the device's camera
     */
    private fun getFocusCapabilities(): String {

        val stringBuilder = StringBuilder()

        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics("0")

        val minFocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val hyperFocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)

        minFocalDistance?.also {
            stringBuilder.append("Minimum Focus Distance: ")
            stringBuilder.append(minFocalDistance)
            stringBuilder.append("\n")
        }

        hyperFocalDistance?.also {
            stringBuilder.append("Maximum Focus Distance: ")
            stringBuilder.append(hyperFocalDistance)
            stringBuilder.append("\n")
        }

        return stringBuilder.toString()
    }

    /**
     * Returns the Hardware Support Level of the device's camera
     * @return A [String] containing the device camera hardware support level
     */
    fun getCameraHardwareSupport(): String {

        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics("0")

        var supportedHardwareLevel = SUPPORT_LEVEL_LEGACY
        when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> supportedHardwareLevel = SUPPORT_LEVEL_LEGACY
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> supportedHardwareLevel = SUPPORT_LEVEL_LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ->  supportedHardwareLevel = SUPPORT_LEVEL_FULL
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> supportedHardwareLevel = SUPPORT_LEVEL_3
        }
        return supportedHardwareLevel
    }

    /**
     * Returns the "Capabilities" of the device's camera as is pertinent to this app
     * @return A [String] containing some pre defined capabilities for a device's camera
     */
    fun getCameraCapabilities(): String {

        val stringBuilder = StringBuilder()

        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // query the CameraManager about the Rear facing camera's characteristics
        val characteristics = cameraManager.getCameraCharacteristics("0")
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.also {
            for (i in it.indices) {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> when (i) {
                        REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> stringBuilder.append("- BACKWARD COMPATIBLE\n")
                        REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> stringBuilder.append("- MANUAL POST PROCESSING\n")
                        REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> stringBuilder.append("- MANUAL SENSOR\n")
                        REQUEST_AVAILABLE_CAPABILITIES_RAW -> stringBuilder.append("- RAW IMAGE FORMAT\n")
                    }
                }
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> when (i) {
                        REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> stringBuilder.append("- BURST CAPTURE\n")
                        REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> stringBuilder.append("- READ SENSOR SETTINGS\n")
                    }
                }
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> when (i) {
                        REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> stringBuilder.append("- PRIVATE REPROCESSING\n")
                        REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> stringBuilder.append("- YUV REPROCESSING\n")
                        REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> stringBuilder.append("- DEPTH OUTPUT\n")
                        REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> stringBuilder.append("- CONSTRAINED HIGH SPEED VIDEO\n")
                    }
                }
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> when (i) {
                        REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> stringBuilder.append("- MOTION TRACKING\n")
                        REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> stringBuilder.append("- LOGICAL MULTI CAMERA\n")
                        REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> stringBuilder.append("- MONOCHROME\n")
                    }
                }
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> when (i) {
                        REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> stringBuilder.append("- SECURE IMAGE DATA\n")
                    }
                }
            }
        }
        return stringBuilder.toString()
    }

    /**
     * Finds the ID of the Rear facing camera
     * @return the ID of the Rear facing camera as a [String]
     */
    private fun getRearFacingCamera(): String? {

        var backCameraId: String? = null
        if (mCameraManager != null) {
            mCameraCharacteristics = mCameraManager?.getCameraCharacteristics("0")
            for (cameraId in (mCameraManager as CameraManager).cameraIdList) {
                val facing = mCameraCharacteristics?.get(CameraCharacteristics.LENS_FACING)
                if (facing != LENS_FACING_FRONT) {
                    backCameraId = cameraId
                    break
                }
            }
        }

        return backCameraId
    }

    // a good place to keep some constants
    companion object {
        const val REQUEST_CAMERA_PERMISSIONS = 0
        const val SUPPORT_LEVEL_LEGACY = "LEGACY"
        const val SUPPORT_LEVEL_LIMITED = "LIMITED"
        const val SUPPORT_LEVEL_FULL = "FULL"
        const val SUPPORT_LEVEL_3 = "THREE"
    }
}