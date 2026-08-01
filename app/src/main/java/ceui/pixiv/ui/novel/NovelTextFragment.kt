package ceui.pixiv.ui.novel

import android.os.Bundle
import android.util.Log
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
                        MenuItem(getString(R.string.translate)) {
                            translate(webNovel.text)
                        }
                    )
                }
            }
        }
    }

    private fun translate(text: String?) {
        if (text.isNullOrEmpty()) {
            return
        }

        // 检查是否正在翻译
        if (isTranslating) {
            Toast.makeText(requireContext(), R.string.translation_in_progress, Toast.LENGTH_SHORT).show()
            return
        }

        // 取消之前的翻译任务（如果有）
        translationJob?.cancel()

        isTranslating = true
        Log.d("Translation", "Starting translation, isTranslating=true")
        val preview = if (text.length <= 50) text else "${text.take(50)}..."
        Log.d("Translation", "Original Text to translate: $preview")

        translationJob = viewLifecycleOwner.lifecycleScope.launch {
            val loadingLayout = binding.loadingLayout
            loadingLayout.visibility = View.VISIBLE
            val loadingText = loadingLayout.findViewById<TextView>(R.id.progress_text)
            loadingText?.text = "正在翻译..."

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
                        textModel.updateNovelText(partialResult)
                    },
                    onProgressUpdate = { completed, total, srcDone, srcTotal ->
                        val pct = if (srcTotal > 0) (srcDone * 100 / srcTotal) else 0
                        loadingText?.text = getString(
                            R.string.translation_progress_detail,
                            completed,
                            total,
                            pct,
                        )
                    },
                )

                textModel.updateNovelText(translatedText)
                Toast.makeText(requireContext(), R.string.translation_complete, Toast.LENGTH_SHORT).show()
                Log.d("Translation", "Translation completed successfully")
            } catch (e: CancellationException) {
                Log.d("Translation", "Translation cancelled by user or lifecycle")
                throw e
            } catch (e: Exception) {
                Log.e("Translation", "Translation failed", e)
                Toast.makeText(requireContext(), R.string.translation_failed, Toast.LENGTH_LONG).show()
            } finally {
                loadingLayout.visibility = View.GONE
                isTranslating = false
                Log.d("Translation", "Translation finished, isTranslating=false")
            }
        }
    }

    override fun onDestroyView() {
        // 取消翻译任务
        Log.d("Translation", "Fragment destroying, cancelling translation job")
        translationJob?.cancel()
        translationJob = null
        isTranslating = false
        super.onDestroyView()
    }
}
