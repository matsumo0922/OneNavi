package me.matsumo.onenavi.car.vd

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Presentation 上で Activity 相当の owner と lifecycle を提供する runtime。 */
class CarVirtualDisplayRuntime :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    NavigationEventDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val runtimeViewModelStore = ViewModelStore()
    private val runtimeNavigationEventDispatcher = NavigationEventDispatcher()
    private var hasRestoredState = false
    private var hasCreated = false
    private var hasStarted = false
    private var hasResumed = false
    private var isDestroyed = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = runtimeViewModelStore

    override val navigationEventDispatcher: NavigationEventDispatcher
        get() = runtimeNavigationEventDispatcher

    fun installViewTreeOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeNavigationEventDispatcherOwner(this)
    }

    fun create(savedInstanceState: Bundle?) {
        if (hasCreated || isDestroyed) {
            return
        }

        restoreStateIfNeeded(savedInstanceState = savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        hasCreated = true
    }

    fun start() {
        if (!hasCreated || hasStarted || isDestroyed) {
            return
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        hasStarted = true
    }

    fun resume() {
        if (!hasCreated || isDestroyed) {
            return
        }

        if (!hasStarted) {
            start()
        }

        if (hasResumed) {
            return
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        hasResumed = true
    }

    fun pause() {
        if (!hasResumed || isDestroyed) {
            return
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        hasResumed = false
    }

    fun stop() {
        if (!hasStarted || isDestroyed) {
            return
        }

        pause()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        hasStarted = false
    }

    fun destroy() {
        if (isDestroyed) {
            return
        }

        stop()
        if (hasCreated) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            hasCreated = false
        }
        runtimeViewModelStore.clear()
        runtimeNavigationEventDispatcher.dispose()
        isDestroyed = true
    }

    fun save(outState: Bundle) {
        if (!hasRestoredState || isDestroyed) {
            return
        }

        savedStateRegistryController.performSave(outState)
    }

    private fun restoreStateIfNeeded(savedInstanceState: Bundle?) {
        if (hasRestoredState) {
            return
        }

        savedStateRegistryController.performRestore(savedInstanceState)
        hasRestoredState = true
    }
}
