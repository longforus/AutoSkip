package top.xjunz.automator

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.xjunz.library.automator.AutomatorConnection
import top.xjunz.library.automator.IAutomatorConnection
import top.xjunz.library.automator.OnSkipListener
import java.io.File
import java.io.FileInputStream


/**
 * @author xjunz 2021/6/26
 */
class AutomatorViewModel : ViewModel() {
    private val tag = "Automator"

    /**
     * Whether we have got the Shizuku permission or not. When [isAvailable] becomes false,
     * this value is also treated as false.
     */
    val isEnabled = MutableLiveData<Boolean>()

    /**
     * Whether our service is running or not. When [isAvailable] becomes false, our service will be
     * killed. Meanwhile, [isEnabled] will not affect this value once our service is started.
     */
    val isRunning = MutableLiveData<Boolean>()

    /**
     * Whether our service is under binding state.
     */
    val isBinding = MutableLiveData<Boolean>()

    /**
     * Whether the Shizuku service is started and we've obtained the binder or not. When this value
     * is false, it means either [isInstalled] is false or [isInstalled] is true while the shizuku
     * service is not started.
     */
    val isAvailable = MutableLiveData<Boolean>().apply {
        observeForever {
            if (it == false) {
                isEnabled.value = false
                updateShizukuInstallationState()
            }
        }
    }

    val error = MutableLiveData<Throwable>()

    val skippedTimes = MutableLiveData<Int>()

    /**
     * Whether the Shizuku client is installed. When this is false, everything is false, of course.
     */
    val isInstalled = MutableLiveData<Boolean>()

    /**
     * The millisecond timestamp when our service is started.
     */
    var serviceStartTimestamp = -1L

    var servicePid = -1

    var filePath: String? = null

    /**
     * Check whether the Shizuku client is installed on this device.
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun updateShizukuInstallationState() {
        viewModelScope.launch {
            isInstalled.value = Dispatchers.Default.invoke {
                AutomatorApp.appContext.packageManager.getInstalledApplications(PackageManager.GET_UNINSTALLED_PACKAGES)
                    .find { it.packageName == AutomatorApp.SHIZUKU_PACKAGE_NAME } != null
            }
        }
    }

    private val serviceNameSuffix = "service"
    private val serviceName = "${BuildConfig.APPLICATION_ID}:$serviceNameSuffix"

    /**
     * "Destroy what you could not control."
     */
    @Suppress("Deprecation")
    fun bindRunningServiceOrKill() = viewModelScope.launch {
        isBinding.value = true
        var bound: Boolean? = null
        withContext(Dispatchers.IO) {
            //Shizuku has no build-in apis to judge whether a user service
            //is still alive or not. Then we fallback using the 'ps' cmd.
            Shizuku.newProcess(arrayOf("ps", "-A", "-o", "pid", "-o", "name"), null, "/")
                .apply {
                    inputStream.bufferedReader().useLines { sequence ->
                        sequence.forEach { line ->
                            //Try bind any service once, if one service has been bound, just kill the other services
                            if (line.endsWith(serviceName)) {
                                val pid = line.trimIndent().split(" ")[0]
                                if (bound == null) {
                                    bound = bindServiceLocked()
                                    if (bound != true) {
                                        killProcess(pid)
                                    }
                                } else {
                                    killProcess(pid)
                                }
                            }
                        }
                    }
                }.destroy()
        }
        isRunning.value = bound == true
        isBinding.value = false
    }

    @Suppress("Deprecation")
    private fun killProcess(pid: String) {
        //outputStream.write() seems not working, have to create a new process
        Shizuku.newProcess(arrayOf("kill", pid), null, "/").apply {
            waitFor()
            destroy()
        }
    }

    private val userServiceStandaloneProcessArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, AutomatorConnection::class.java.name))
            .processNameSuffix(serviceNameSuffix).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }

    private val deathRecipient by lazy {
        IBinder.DeathRecipient {
            Log.i(tag, "Service is dead!")
            isRunning.postValue(false)
        }
    }
    private var automatorService: IAutomatorConnection? = null
    private val userServiceConnection by lazy {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                synchronized(lock) {
                    if (binder != null && binder.pingBinder()) {
                        automatorService = IAutomatorConnection.Stub.asInterface(binder)
                        automatorService?.run {
                            try {
                                Log.i(tag, sayHello())
                                if (!isConnected) {
                                    connect()
                                    setFileDescriptors(ParcelFileDescriptor.open(File(filePath!!),
                                        ParcelFileDescriptor.MODE_READ_WRITE
                                                or ParcelFileDescriptor.MODE_APPEND.inv()))
                                    Log.i(tag, "Automator connected successfully!")
                                }
                                binder.linkToDeath(deathRecipient, 0)
                                setOnSkipListener(object : OnSkipListener.Stub() {
                                    override fun onSkip(times: Int, type: Int, node: AccessibilityNodeInfo?) {
                                        this@AutomatorViewModel.skippedTimes.postValue(times)
                                        Log.i(tag, "skipped time: $times, type: $type, node: $node")
                                    }
                                })
                                this@AutomatorViewModel.skippedTimes.value = skippedTimes
                                servicePid = pid
                                serviceStartTimestamp = startTimestamp
                                isRunning.value = true
                            } catch (t: Throwable) {
                                t.printStackTrace()
                                error.value = t
                                //This happens when our service is still running while the binder
                                //is disconnected or our service dead unexpectedly when starting.
                                //Anyway, unbind the service and kill it.
                                if (t is DeadObjectException) {
                                    if (!unbindService(true) && servicePid > 0) {
                                        killProcess(servicePid.toString())
                                    }
                                }
                            }
                        }
                    }
                    isBinding.value = false
                    lock.notifyAll()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isRunning.value = false
            }
        }
    }

    private val lock = Object()

    /**
     * Bind the service and wait the service to return the binding result.
     */
    private fun bindServiceLocked(): Boolean = synchronized(lock) {
        try {
            Shizuku.bindUserService(userServiceStandaloneProcessArgs, userServiceConnection)
            lock.wait(6180)
        } catch (t: Throwable) {
            t.printStackTrace()
            error.value = t
            return false
        }
        return isRunning.value == true
    }


    private fun bindService() = try {
        isBinding.value = true
        Shizuku.bindUserService(userServiceStandaloneProcessArgs, userServiceConnection)
        true
    } catch (t: Throwable) {
        t.printStackTrace()
        error.value = t
        isBinding.value = false
        false
    }

    fun unbindService(kill: Boolean): Boolean = try {
        Shizuku.unbindUserService(userServiceStandaloneProcessArgs, userServiceConnection, kill)
        isRunning.value = false
        true
    } catch (t: Throwable) {
        t.printStackTrace()
        error.value = t
        false
    }

    fun toggleService() {
        if (isRunning.value == true) {
            unbindService(true)
        } else {
            //clear cache in the Shizuku connection pool first
            // to avoid binding a cached but dead service
            // unbindService(false)
            bindService()
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun initSkippedTimes(file: File) = viewModelScope.launch {
        filePath = file.path
        var times = 0
        withContext(Dispatchers.IO) {
            if (file.exists()) {
                FileInputStream(file).bufferedReader().useLines {
                    it.forEach { line ->
                        times = line.toIntOrNull() ?: 0
                        return@useLines
                    }
                }
            }
        }
        skippedTimes.value = times
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky {
            isAvailable.value = true
            isEnabled.value = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (isEnabled.value == true) {
                bindRunningServiceOrKill()
            }
        }
        Shizuku.addBinderDeadListener {
            isAvailable.value = false
            //binder is dead, there is no way to judge whether the service is still running
        }
    }

    private inline fun safeDo(block: () -> Unit): Boolean {
        automatorService?.let {
            it.asBinder().run {
                if (pingBinder()) {
                    block.invoke()
                    return true
                }
            }
        }
    }

    fun pauseService(): Boolean {
        automatorService?.let {
            it.asBinder().run {
                if (pingBinder()) {
                    it.pause()
                    return true
                }
            }
        }
        return false
    }

    fun stopTesting(): Boolean {
        automatorService?.let {
            it.asBinder().run {
                if (pingBinder()) {
                    it.stopTestMonitoring()
                    return true
                }
            }
        }
        return false
    }

    fun detachFromService() {
        automatorService?.let {
            it.asBinder().run {
                if (pingBinder()) {
                    unlinkToDeath(deathRecipient, 0)
                    it.removeOnSkipListener()
                }
            }
        }
        unbindService(false)
    }
}