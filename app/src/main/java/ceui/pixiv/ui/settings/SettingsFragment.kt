package ceui.pixiv.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.loxia.requireAppBackground
import ceui.loxia.requireTaskPool
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.background.BackgroundType
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.translation.TranslationApiKeyStore
import ceui.pixiv.translation.TranslationCacheStore
import ceui.pixiv.translation.TranslationErrorMessages
import ceui.pixiv.translation.TranslationManager
import ceui.pixiv.translation.TranslationSettingsStore
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.pixiv.utils.GSON_DEFAULT
import ceui.pixiv.widgets.alertYesOrCancel
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsFragment : PixivFragment(R.layout.fragment_pixiv_list), LogOutActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val prefStore: MMKV by lazy {
        MMKV.mmkvWithID("shaft-session")
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_TABCELL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.app_settings)
        val liveUser = ObjectPool.get<User>(SessionManager.loggedInUid)
        prefStore.getString(SessionManager.COOKIE_KEY, "") ?: ""
        val nameCode = "cn"
        val activityCtx = requireActivity()

        fun buildSettingsList(): List<ListItemHolder> {
            val backgroundType = requireAppBackground().config.value?.type
            return listOf(
                TabCellHolder(
                    getString(R.string.view_and_artworks_display),
                    getString(R.string.handle_r18g_displaying),
                ).onItemClick {
                    pushFragment(
                        R.id.navigation_web_fragment,
                        WebFragmentArgs("https://www.pixiv.net/settings/viewing").toBundle(),
                    )
                },

                TabCellHolder(
                    getString(R.string.app_background),
                    extraInfo = if (backgroundType == BackgroundType.SPECIFIC_ILLUST) {
                        getString(R.string.background_specified_illust)
                    } else if (backgroundType == BackgroundType.LOCAL_FILE) {
                        getString(R.string.background_chosen_from_gallary)
                    } else {
                        backgroundType?.toString()
                    },
                ).onItemClick {
                    pushFragment(
                        R.id.navigation_background_settings,
                    )
                },

                TabCellHolder(
                    getString(R.string.country_and_region),
                    getString(R.string.handle_content_language),
                    nameCode,
                ).onItemClick {
                    pushFragment(
                        R.id.navigation_select_country,
                    )
                },

                TabCellHolder(
                    getString(R.string.language),
                    getString(R.string.handle_content_language),
                    Shaft.sSettings.appLanguage,
                ).onItemClick {
                    pushFragment(
                        R.id.navigation_select_language,
                    )
                },

                TabCellHolder(
                    getString(R.string.translation_xai_api_key_title),
                    getString(R.string.translation_xai_api_key_summary),
                    TranslationApiKeyStore.getSettingsSummary(
                        getString(R.string.translation_xai_api_key_not_set),
                    ),
                ).onItemClick {
                    val edit = EditText(requireContext()).apply {
                        hint = getString(R.string.translation_xai_api_key_hint)
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD
                        isSingleLine = true
                    }
                    val pad = (16 * resources.displayMetrics.density).roundToInt()
                    val container = FrameLayout(requireContext()).apply {
                        setPadding(pad, pad / 2, pad, pad / 2)
                        addView(edit)
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.translation_xai_api_key_title)
                        .setView(container)
                        .setPositiveButton(R.string.string_190) { _, _ ->
                            val enteredKey = edit.text?.toString().orEmpty().trim()
                            if (enteredKey.isEmpty()) {
                                return@setPositiveButton
                            }
                            if (TranslationApiKeyStore.setApiKey(enteredKey)) {
                                adapter.submitList(buildSettingsList())
                            } else {
                                Common.showToast(getString(R.string.translation_api_key_save_failed), 2)
                            }
                        }
                        .setNeutralButton(R.string.translation_api_key_clear) { _, _ ->
                            TranslationApiKeyStore.setApiKey("")
                            adapter.submitList(buildSettingsList())
                            Common.showToast(getString(R.string.translation_api_key_cleared), 2)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },

                TabCellHolder(
                    getString(R.string.translation_model_title),
                    getString(R.string.translation_model_summary),
                    TranslationSettingsStore.getModel(),
                ).onItemClick {
                    val edit = EditText(requireContext()).apply {
                        setText(TranslationSettingsStore.getModel())
                        isSingleLine = true
                        selectAll()
                    }
                    val pad = (16 * resources.displayMetrics.density).roundToInt()
                    val container = FrameLayout(requireContext()).apply {
                        setPadding(pad, pad / 2, pad, pad / 2)
                        addView(edit)
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.translation_model_title)
                        .setView(container)
                        .setPositiveButton(R.string.string_190) { _, _ ->
                            if (TranslationSettingsStore.setModel(edit.text?.toString().orEmpty())) {
                                adapter.submitList(buildSettingsList())
                            } else {
                                Common.showToast(getString(R.string.translation_model_invalid), 2)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },

                TabCellHolder(
                    getString(R.string.translation_test_connection),
                    getString(R.string.translation_test_connection_summary),
                ).onItemClick {
                    val apiKey = TranslationApiKeyStore.getApiKey()
                    if (apiKey.isEmpty()) {
                        Common.showToast(getString(R.string.translation_xai_api_key_required), 2)
                        return@onItemClick
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            TranslationManager.getInstance().validateConfiguration(apiKey)
                        }.onSuccess {
                            Common.showToast(getString(R.string.translation_test_success), 2)
                        }.onFailure { error ->
                            Common.showToast(TranslationErrorMessages.get(requireContext(), error), 2)
                        }
                    }
                },

                TabCellHolder(
                    getString(R.string.translation_cache_clear),
                    getString(R.string.translation_cache_clear_summary),
                ).onItemClick {
                    if (TranslationCacheStore.clear(requireContext())) {
                        Common.showToast(getString(R.string.translation_cache_cleared), 2)
                    }
                },

                TabCellHolder(
                    getString(R.string.export_refresh_token),
                    extraInfo = SessionManager.loggedInAccount.value?.refresh_token,
                ).onItemClick {
                    SessionManager.loggedInAccount.value?.refresh_token?.let { token ->
                        Common.copy(activityCtx, token)
                    }
                },

                TabCellHolder(
                    getString(R.string.export_logged_in_user_json),
                    extraInfo = "[JSON FORMATTED]",
                ).onItemClick {
                    SessionManager.loggedInAccount.value?.let { account ->
                        Common.copy(activityCtx, GSON_DEFAULT.toJson(account))
                    }
                },

                TabCellHolder(
                    "Landing Page Preview",
                ).onItemClick {
                    pushFragment(
                        R.id.navigation_landing,
                    )
                },

                TabCellHolder(
                    getString(R.string.full_about_app),
                ).onItemClick {
                    pushFragment(
                        R.id.navigation_about_app,
                    )
                },

                LogOutHolder(),
            )
        }

        liveUser.observe(viewLifecycleOwner) {
            adapter.submitList(buildSettingsList())
        }
    }

    override fun onClickLogOut(sender: ProgressIndicator) {
        launchSuspend(sender) {
            val taskPool = requireTaskPool()
            val prefStore = MMKV.mmkvWithID("api-cache-${SessionManager.loggedInUid}")
            if (alertYesOrCancel("确定退出登录吗")) {
                prefStore.clearAll()
                taskPool.clearTasks()
                SessionManager.updateSession(null)
                findNavController().navigate(
                    R.id.navigation_landing,
                    null, // 如果有参数需要传递，可以用 Bundle 替代 null
                    NavOptions.Builder()
                        .setPopUpTo(R.id.mobile_navigation, true) // 清除栈中所有页面
                        .setLaunchSingleTop(true) // 防止重复创建 D
                        .build()
                )
            }
        }
    }
}
