/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.customtabs

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.state.state.ExternalAppType
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.feature.customtabs.CustomTabWindowFeature
import mozilla.components.feature.pwa.feature.ManifestUpdateFeature
import mozilla.components.feature.pwa.feature.WebAppActivityFeature
import mozilla.components.feature.pwa.feature.WebAppContentFeature
import mozilla.components.feature.pwa.feature.WebAppHideToolbarFeature
import mozilla.components.feature.pwa.feature.WebAppSiteControlsFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.arch.lifecycle.addObservers
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.BaseBrowserFragment
import org.mozilla.fenix.browser.ContextMenuSnackbarDelegate
import org.mozilla.fenix.browser.CustomTabContextMenuCandidate
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.components.toolbar.BrowserToolbarView
import org.mozilla.fenix.components.toolbar.ToolbarMenu
import org.mozilla.fenix.components.toolbar.ToolbarPosition
import org.mozilla.fenix.components.toolbar.navbar.CustomTabNavBar
import org.mozilla.fenix.compose.Divider
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.nav
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.runIfFragmentIsAttached
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.settings.quicksettings.protections.cookiebanners.getCookieBannerUIMode
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Fragment used for browsing the web within external apps.
 */
class ExternalAppBrowserFragment : BaseBrowserFragment() {

    private val args by navArgs<ExternalAppBrowserFragmentArgs>()

    private val customTabsIntegration = ViewBoundFeatureWrapper<CustomTabsIntegration>()
    private val windowFeature = ViewBoundFeatureWrapper<CustomTabWindowFeature>()
    private val hideToolbarFeature = ViewBoundFeatureWrapper<WebAppHideToolbarFeature>()

    private val isNavBarEnabled
        get() = requireContext().settings().navigationToolbarEnabled

    @Suppress("LongMethod", "ComplexMethod")
    override fun initializeUI(view: View, tab: SessionState) {
        super.initializeUI(view, tab)

        val customTabSessionId = customTabSessionId ?: return
        val activity = requireActivity()
        val components = activity.components

        val manifest = args.webAppManifestUrl?.ifEmpty { null }?.let { url ->
            requireComponents.core.webAppManifestStorage.getManifestCache(url)
        }

        initializeNavBar()

        customTabsIntegration.set(
            feature = CustomTabsIntegration(
                context = requireContext(),
                store = requireComponents.core.store,
                appStore = requireComponents.appStore,
                useCases = requireComponents.useCases.customTabsUseCases,
                browserToolbarView = browserToolbarView,
                sessionId = customTabSessionId,
                activity = activity,
                interactor = browserToolbarInteractor,
                isPrivate = tab.content.private,
                shouldReverseItems = !activity.settings().shouldUseBottomToolbar,
                isSandboxCustomTab = args.isSandboxCustomTab,
                isMenuRedesignEnabled = requireContext().settings().enableMenuRedesign,
                isNavBarEnabled = isNavBarEnabled,
            ),
            owner = this,
            view = view,
        )

        windowFeature.set(
            feature = CustomTabWindowFeature(activity, components.core.store, customTabSessionId),
            owner = this,
            view = view,
        )

        val customTabSession = (tab as? CustomTabSessionState)
        val isPwaTabOrTwaTab = customTabSession?.config?.externalAppType == ExternalAppType.PROGRESSIVE_WEB_APP ||
            customTabSession?.config?.externalAppType == ExternalAppType.TRUSTED_WEB_ACTIVITY

        // Only set hideToolbarFeature if isPwaTabOrTwaTab
        if (isPwaTabOrTwaTab) {
            hideToolbarFeature.set(
                feature = WebAppHideToolbarFeature(
                    store = requireComponents.core.store,
                    customTabsStore = requireComponents.core.customTabsStore,
                    tabId = customTabSessionId,
                    manifest = manifest,
                ) { toolbarVisible ->
                    webAppToolbarShouldBeVisible = toolbarVisible
                    when (toolbarVisible) {
                        true -> collapseBrowserView()
                        false -> expandBrowserView()
                    }
                },
                owner = this,
                view = view,
            )
        }

        if (manifest != null) {
            activity.lifecycle.addObservers(
                WebAppActivityFeature(
                    activity,
                    components.core.icons,
                    manifest,
                ),
                WebAppContentFeature(
                    store = requireComponents.core.store,
                    tabId = customTabSessionId,
                    manifest,
                ),
                ManifestUpdateFeature(
                    activity.applicationContext,
                    requireComponents.core.store,
                    requireComponents.core.webAppShortcutManager,
                    requireComponents.core.webAppManifestStorage,
                    customTabSessionId,
                    manifest,
                ),
            )
            viewLifecycleOwner.lifecycle.addObserver(
                WebAppSiteControlsFeature(
                    activity.applicationContext,
                    requireComponents.core.store,
                    requireComponents.useCases.sessionUseCases.reload,
                    customTabSessionId,
                    manifest,
                    WebAppSiteControlsBuilder(
                        requireComponents.core.store,
                        requireComponents.useCases.sessionUseCases.reload,
                        customTabSessionId,
                        manifest,
                    ),
                    notificationsDelegate = requireComponents.notificationsDelegate,
                ),
            )
        } else {
            viewLifecycleOwner.lifecycle.addObserver(
                PoweredByNotification(
                    activity.applicationContext,
                    requireComponents.core.store,
                    customTabSessionId,
                    requireComponents.notificationsDelegate,
                ),
            )
        }
    }

    override fun onUpdateToolbarForConfigurationChange(toolbar: BrowserToolbarView) {
        super.onUpdateToolbarForConfigurationChange(toolbar)
        initializeNavBar()
    }

    override fun removeSessionIfNeeded(): Boolean {
        return customTabsIntegration.onBackPressed() || super.removeSessionIfNeeded()
    }

    override fun navToQuickSettingsSheet(tab: SessionState, sitePermissions: SitePermissions?) {
        requireComponents.useCases.trackingProtectionUseCases.containsException(tab.id) { contains ->
            lifecycleScope.launch {
                val cookieBannersStorage = requireComponents.core.cookieBannersStorage
                val cookieBannerUIMode = cookieBannersStorage.getCookieBannerUIMode(
                    requireContext(),
                    tab,
                )
                withContext(Dispatchers.Main) {
                    runIfFragmentIsAttached {
                        val directions = ExternalAppBrowserFragmentDirections
                            .actionGlobalQuickSettingsSheetDialogFragment(
                                sessionId = tab.id,
                                url = tab.content.url,
                                title = tab.content.title,
                                isSecured = tab.content.securityInfo.secure,
                                sitePermissions = sitePermissions,
                                gravity = getAppropriateLayoutGravity(),
                                certificateName = tab.content.securityInfo.issuer,
                                permissionHighlights = tab.content.permissionHighlights,
                                isTrackingProtectionEnabled = tab.trackingProtection.enabled && !contains,
                                cookieBannerUIMode = cookieBannerUIMode,
                            )
                        nav(R.id.externalAppBrowserFragment, directions)
                    }
                }
            }
        }
    }

    override fun getContextMenuCandidates(
        context: Context,
        view: View,
    ): List<ContextMenuCandidate> = CustomTabContextMenuCandidate.defaultCandidates(
        context,
        context.components.useCases.contextMenuUseCases,
        view,
        ContextMenuSnackbarDelegate(),
    )

    private fun initializeNavBar() {
        // Update the contents of the bottomToolbarContainer with the CustomTabNavBar configuration
        // only if the container was initialized in the parent - we know a navbar should be used.
        // Follow up: https://bugzilla.mozilla.org/show_bug.cgi?id=1888300
        _bottomToolbarContainerView ?: return

        val customTabSessionId = customTabSessionId ?: return

        val navbarIntegration = CustomTabsNavigationBarIntegration(
            context = requireContext(),
            browserStore = requireComponents.core.store,
            customTabSessionId = customTabSessionId,
            toolbar = browserToolbarView,
        )

        val isToolbarAtBottom = requireComponents.settings.toolbarPosition == ToolbarPosition.BOTTOM
        bottomToolbarContainerView.updateContent {
            FirefoxTheme {
                val background = navbarIntegration.backgroundColor?.let { Color(it) } ?: FirefoxTheme.colors.layer1
                Column(
                    modifier = Modifier.background(background),
                ) {
                    if (isToolbarAtBottom) {
                        // If the toolbar is reinitialized - for example after the screen is rotated
                        // the toolbar might have been already set.
                        (browserToolbarView.view.parent as? ViewGroup)?.removeView(browserToolbarView.view)
                        AndroidView(factory = { _ -> browserToolbarView.view })
                    } else {
                        Divider()
                    }

                    CustomTabNavBar(
                        customTabSessionId = customTabSessionId,
                        browserStore = requireComponents.core.store,
                        menuButton = navbarIntegration.navbarMenu,
                        onBackButtonClick = {
                            browserToolbarInteractor.onBrowserToolbarMenuItemTapped(
                                ToolbarMenu.Item.Back(viewHistory = false),
                            )
                        },
                        onBackButtonLongPress = {
                            browserToolbarInteractor.onBrowserToolbarMenuItemTapped(
                                ToolbarMenu.Item.Back(viewHistory = true),
                            )
                        },
                        onForwardButtonClick = {
                            browserToolbarInteractor.onBrowserToolbarMenuItemTapped(
                                ToolbarMenu.Item.Forward(viewHistory = false),
                            )
                        },
                        onForwardButtonLongPress = {
                            browserToolbarInteractor.onBrowserToolbarMenuItemTapped(
                                ToolbarMenu.Item.Forward(viewHistory = true),
                            )
                        },
                        onOpenInBrowserButtonClick = {
                            browserToolbarInteractor.onBrowserToolbarMenuItemTapped(
                                ToolbarMenu.Item.OpenInFenix,
                            )
                        },
                        onMenuButtonClick = {
                            nav(
                                R.id.externalAppBrowserFragment,
                                ExternalAppBrowserFragmentDirections.actionGlobalMenuDialogFragment(
                                    accesspoint = MenuAccessPoint.External,
                                    customTabSessionId = customTabSessionId,
                                ),
                            )
                        },
                        backgroundColor = background,
                        buttonTint = navbarIntegration.buttonTint,
                        buttonDisabledTint = navbarIntegration.buttonDisabledTint,
                        onVisibilityUpdated = {
                            configureEngineViewWithDynamicToolbarsMaxHeight()
                        },
                    )
                }
            }
        }
    }
}
