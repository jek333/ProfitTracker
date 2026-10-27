package com.jek.sq5monitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.Sq5MonitorApp

/** Creates a ViewModel with access to the app's [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as Sq5MonitorApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
