package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.translation.TranslationApiKeyStore
import ceui.pixiv.translation.TranslationConfig
import ceui.pixiv.translation.TranslationErrorMessages
import ceui.pixiv.translation.TranslationManager
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.DownloadNovelTask
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment,
    NovelSeriesActionReceiver {

    private val safeArgs by navArgs<NovelTextFragmentArgs>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val textModel by constructVM({ safeArgs.novelId }) { novelId ->
        NovelTextViewModel(novelId)
    }

    // 翻译状态管理
    private var translationJob: Job? = null
    private var isTranslating = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, textModel, listMode = ListMode.VERTICAL_NOVEL)

        // 初始化翻译配置
        TranslationConfig.getInstance().loadConfig(requireContext())

        val liveNovel = ObjectPool.get<Novel>(safeArgs.novelId)
        combineLatest(
            liveNovel,
            textModel.webNovel
        ).observe(viewLifecycleOwner) { (novel, webNovel) ->

            if (novel != null) {
                runOnceWithinFragmentLifecycle("visit-novel-${safeArgs.novelId}") {
                    requireEntityWrapper().visitNovel(requireContext(), novel)
                }
            }

            binding.toolbarLayout.naviMore.setOnClick {
                if (novel == null || webNovel == null) {
                    return@setOnClick
                }

                val authorId = novel.user?.id ?: 0L
                showActionMenu {
                    add(
                        MenuItem(getString(R.string.view_comments)) {
                            pushFragment(
                                R.id.navigation_comments_illust,
                                CommentsFragmentArgs(
                                    safeArgs.novelId,
                                    authorId,
                                    ObjectType.NOVEL
                                ).toBundle()
                            )
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_110)) {
                            shareNovel(novel)
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_5)) {
                            DownloadNovelTask(
                                requireActivity().lifecycleScope,
                                novel,
                                webNovel
                            ).start {

                            }
                        }
                    )
                    add(
                        MenuItem(
                            getString(
                                if (isTranslating) R.string.translation_cancel else R.string.translate,
                            ),
                        ) {
                            if (isTranslating) {
                                cancelTranslation(showMessage = true)
                            } else {
                                translate(textModel.sourceText())
                            }
                        }
                    )
                    if (textModel.hasTranslation) {
                        add(
                            MenuItem(
                                getString(
                                    if (textModel.isShowingTranslation) {
                                        R.string.translation_show_original
                                    } else {
                                        R.string.translation_show_result
                                    },
                                ),
                            ) {
                                if (textModel.isShowingTranslation) {
                                    textModel.showOriginal()
                                } else {
                                    textModel.showTranslation()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun translate(text: String?) {
        if (text.isNullOrEmpty()) {
            return
        }

        if (isTranslating) {
            cancelTranslation(showMessage = true)
            return
        }

        translationJob?.cancel()

        isTranslating = true

        translationJob = viewLifecycleOwner.lifecycleScope.launch {
            val loadingLayout = binding.loadingLayout
            loadingLayout.visibility = View.VISIBLE
            val loadingText = loadingLayout.findViewById<TextView>(R.id.progress_text)
            var progressLabel = getString(R.string.translation_in_progress)
            var previewLabel = ""

            fun renderProgress() {
                loadingText?.text = if (previewLabel.isEmpty()) {
                    progressLabel
                } else {
                    getString(R.string.translation_preview, progressLabel, previewLabel)
                }
            }
            renderProgress()

            try {
                val apiKey = TranslationApiKeyStore.getApiKey()
                if (apiKey.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translation_xai_api_key_required),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                val translationManager = TranslationManager.getInstance()
                val translatedText = translationManager.translate(
                    text = text,
                    apiKey = apiKey,
                    from = "Japanese",
                    to = "Chinese",
                    context = requireContext(),
                    onProgress = { partialResult ->
                        previewLabel = partialResult
                            .takeLast(120)
                            .replace('\n', ' ')
                            .trim()
                        renderProgress()
                    },
                    onProgressUpdate = { completed, total, srcDone, srcTotal ->
                        val pct = if (srcTotal > 0) (srcDone * 100 / srcTotal) else 0
                        progressLabel = getString(
                            R.string.translation_progress_detail,
                            completed,
                            total,
                            pct,
                        )
                        renderProgress()
                    },
                )

                textModel.applyTranslation(translatedText)
                Toast.makeText(requireContext(), R.string.translation_complete, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    TranslationErrorMessages.get(requireContext(), e),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                loadingLayout.visibility = View.GONE
                isTranslating = false
                translationJob = null
            }
        }
    }

    private fun cancelTranslation(showMessage: Boolean) {
        if (!isTranslating) return
        translationJob?.cancel()
        if (showMessage && isAdded) {
            Toast.makeText(requireContext(), R.string.translation_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        cancelTranslation(showMessage = false)
        translationJob = null
        isTranslating = false
        super.onDestroyView()
    }
}
