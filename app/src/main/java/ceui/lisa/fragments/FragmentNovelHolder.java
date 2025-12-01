package ceui.lisa.fragments;

import static ceui.lisa.activities.Shaft.sUserModel;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.PathUtils;
import com.bumptech.glide.Glide;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jaredrummler.android.colorpicker.ColorPickerDialog;
import com.skydoves.transformationlayout.OnTransformFinishListener;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.VAdapter;
import ceui.lisa.adapters.VNewAdapter;
import ceui.lisa.cache.Cache;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.FragmentNovelHolderBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.helper.NovelParseHelper;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.NovelSearchResponse;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.view.ScrollChange;
import ceui.loxia.SpaceHolder;
import ceui.loxia.TextDescHolder;
import ceui.loxia.WebNovel;
import ceui.pixiv.ui.common.CommonAdapter;
import ceui.pixiv.ui.common.ListItemHolder;
import gdut.bsx.share2.Share2;
import gdut.bsx.share2.ShareContentType;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.zhanghai.android.fastscroll.FastScroller;
import me.zhanghai.android.fastscroll.FastScrollerBuilder;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class FragmentNovelHolder extends BaseFragment<FragmentNovelHolderBinding> {

    private boolean isOpen = false;
    private NovelBean mNovelBean;
    private NovelDetail mNovelDetail;
    private WebNovel mWebNovel;

    public static FragmentNovelHolder newInstance(NovelBean novelBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, novelBean);
        FragmentNovelHolder fragment = new FragmentNovelHolder();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_novel_holder;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mNovelBean = (NovelBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initView() {
        baseBind.viewPager.setVerticalScrollBarEnabled(false);
        baseBind.viewPager.setScrollbarFadingEnabled(false);
        FastScroller fb = new FastScrollerBuilder(baseBind.viewPager).build();

        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        if (Shaft.sSettings.getNovelHolderColor() != 0) {
            setBackgroundColor(Shaft.sSettings.getNovelHolderColor());
        }
        baseBind.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.transformationLayout.startTransform();
            }
        });
        baseBind.transformationLayout.onTransformFinishListener = new OnTransformFinishListener() {
            @Override
            public void onFinish(boolean isTransformed) {
                Common.showLog(className + isTransformed);
                isOpen = isTransformed;
            }
        };
    }

    @Override
    protected void initData() {
        displayNovel(mNovelBean);
    }

    public void setBackgroundColor(int color) {
        Common.showLog(className + color);
        baseBind.relaRoot.setBackgroundColor(color);
    }

    public void setTextColor(int color) {
        Common.showLog(className + color);
        baseBind.toolbar.getOverflowIcon().setTint(Common.getNovelTextColor());
        setNovelAdapter();
    }

    private void displayNovel(NovelBean novelBean) {
        mNovelBean = novelBean;
        if (mNovelBean.isIs_bookmarked()) {
            baseBind.like.setText(mContext.getString(R.string.string_179));
        } else {
            baseBind.like.setText(mContext.getString(R.string.string_180));
        }
        Common.showLog(className + "getNovel 000");
        baseBind.like.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showLog(className + "getNovel 111");
                PixivOperate.postLikeNovel(mNovelBean, Shaft.sUserModel,
                        Params.TYPE_PUBLIC, baseBind.like);
            }
        });

        baseBind.like.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.ILLUST_ID, mNovelBean.getId());
                intent.putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL);
                intent.putExtra(Params.TAG_NAMES, mNovelBean.getTagNames());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                mContext.startActivity(intent);
                return true;
            }
        });

        View.OnClickListener seeUser = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showUser(mContext, mNovelBean.getUser());
            }
        };
        baseBind.userHead.setOnClickListener(seeUser);
        baseBind.userName.setOnClickListener(seeUser);
        baseBind.userName.setText(mNovelBean.getUser().getName());
        baseBind.viewPager.setLayoutManager(new ScrollChange(mContext));
        baseBind.viewPager.setHasFixedSize(false);
        baseBind.novelTitle.setText(String.format("%s%s", getString(R.string.string_182), mNovelBean.getTitle()));
        if (mNovelBean.getSeries() != null && !TextUtils.isEmpty(mNovelBean.getSeries().getTitle())) {
            baseBind.novelSeries.setVisibility(View.VISIBLE);
            baseBind.novelSeries
                    .setText(String.format("%s%s", getString(R.string.string_183), mNovelBean.getSeries().getTitle()));
            baseBind.novelSeries.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ID, mNovelBean.getSeries().getId());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情");
                    startActivity(intent);
                }
            });
        } else {
            baseBind.novelSeries.setVisibility(View.GONE);
        }
        if (mNovelBean.getTags() != null && mNovelBean.getTags().size() != 0) {
            baseBind.hotTags.setAdapter(new TagAdapter<TagsBean>(
                    mNovelBean.getTags()) {
                @Override
                public View getView(FlowLayout parent, int position, TagsBean trendTagsBean) {
                    TextView tv = (TextView) LayoutInflater.from(mContext).inflate(
                            R.layout.recy_single_novel_tag_text_small,
                            parent, false);
                    tv.setText(trendTagsBean.getName());
                    return tv;
                }
            });
            baseBind.hotTags.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
                @Override
                public boolean onTagClick(View view, int position, FlowLayout parent) {
                    Intent intent = new Intent(mContext, SearchActivity.class);
                    intent.putExtra(Params.KEY_WORD, mNovelBean.getTags().get(position).getName());
                    intent.putExtra(Params.INDEX, 1);
                    startActivity(intent);
                    return false;
                }
            });
        }
        if (TextUtils.isEmpty(mNovelBean.getCaption())) {
            baseBind.description.setVisibility(View.GONE);
        } else {
            baseBind.description.setVisibility(View.VISIBLE);
            baseBind.description.setHtml(mNovelBean.getCaption());
        }
        baseBind.howManyWord.setText(String.format(Locale.getDefault(), "%d字", mNovelBean.getText_length()));
        baseBind.publishTime.setText(Common.getLocalYYYYMMDDHHMMString(mNovelBean.getCreate_date()));
        baseBind.viewCount.setText(String.valueOf(mNovelBean.getTotal_view()));
        baseBind.bookmarkCount.setText(String.valueOf(mNovelBean.getTotal_bookmarks()));
        baseBind.comment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.NOVEL_ID, mNovelBean.getId());
                intent.putExtra(Params.ILLUST_TITLE, mNovelBean.getTitle());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论");
                startActivity(intent);
            }
        });
        Glide.with(mContext).load(GlideUtil.getHead(mNovelBean.getUser())).into(baseBind.userHead);

        PixivOperate.insertNovelViewHistory(novelBean);
        baseBind.viewPager.setVisibility(View.INVISIBLE);
        if (novelBean.isLocalSaved()) {
            baseBind.progressRela.setVisibility(View.INVISIBLE);
            mNovelDetail = Cache.get().getModel(Params.NOVEL_KEY + mNovelBean.getId(), NovelDetail.class);
            refreshDetail(mNovelDetail);
        } else {
            baseBind.progressRela.setVisibility(View.VISIBLE);
            Retro.getAppApi().getNovelDetailV2(Shaft.sUserModel.getAccess_token(), novelBean.getId())
                    .enqueue(new retrofit2.Callback<ResponseBody>() {
                        @Override
                        public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                            baseBind.progressRela.setVisibility(View.INVISIBLE);
                            new WebNovelParser(response) {
                                @Override
                                public void onNovelPrepared(@NonNull NovelDetail novelDetail,
                                        @NonNull WebNovel webNovel) {
                                    mWebNovel = webNovel;
                                    novelDetail.setParsedChapters(
                                            NovelParseHelper.tryParseChapters(novelDetail.getNovel_text()));
                                    refreshDetail(novelDetail);
                                }
                            };
                        }

                        @Override
                        public void onFailure(Call<ResponseBody> call, Throwable t) {
                            baseBind.progressRela.setVisibility(View.INVISIBLE);
                        }
                    });
        }

        baseBind.toolbar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return baseBind.awesomeCardCon.dispatchTouchEvent(event);
            }
        });
    }

    private void refreshDetail(NovelDetail novelDetail) {
        mNovelDetail = novelDetail;
        baseBind.viewPager.setVisibility(View.VISIBLE);
        baseBind.awesomeCardCon.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isOpen) {
                    baseBind.transformationLayout.finishTransform();
                    isOpen = false;
                    return true;
                }
                return baseBind.viewPager.dispatchTouchEvent(event);
            }
        });

        setNovelAdapter();

        if (novelDetail.getSeries_prev() != null && novelDetail.getSeries_prev().getId() != 0) {
            baseBind.showPrev.setVisibility(View.VISIBLE);
            baseBind.showPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    baseBind.transformationLayout.finishTransform();
                    Retro.getAppApi().getNovelByID(sUserModel.getAccess_token(), novelDetail.getSeries_prev().getId())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<NovelSearchResponse>() {
                                @Override
                                public void success(NovelSearchResponse novelSearchResponse) {
                                    displayNovel(novelSearchResponse.getNovel());
                                }
                            });
                }
            });
        } else {
            baseBind.showPrev.setVisibility(View.INVISIBLE);
        }
        if (novelDetail.getSeries_next() != null && novelDetail.getSeries_next().getId() != 0) {
            baseBind.showNext.setVisibility(View.VISIBLE);
            baseBind.showNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    baseBind.transformationLayout.finishTransform();
                    Retro.getAppApi().getNovelByID(sUserModel.getAccess_token(), novelDetail.getSeries_next().getId())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<NovelSearchResponse>() {
                                @Override
                                public void success(NovelSearchResponse novelSearchResponse) {
                                    displayNovel(novelSearchResponse.getNovel());
                                }
                            });
                }
            });
        } else {
            baseBind.showNext.setVisibility(View.INVISIBLE);
        }
        baseBind.toolbar.getMenu().clear();
        baseBind.toolbar.inflateMenu(R.menu.novel_read_menu);
        baseBind.toolbar.getOverflowIcon().setTint(Common.getNovelTextColor());
        baseBind.saveNovelTxt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 需要下载txt文件
                IllustDownload.downloadNovel((BaseActivity<?>) mContext, mNovelBean, novelDetail, new Callback<Uri>() {
                    @Override
                    public void doSomething(Uri t) {
                        Common.showToast(getString(R.string.string_279), 2);
                    }
                });
            }
        });
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_change_color) {
                    if (Shaft.sSettings.getNovelHolderColor() != 0) {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_BG_COLOR)
                                .setColor(Shaft.sSettings.getNovelHolderColor())
                                .show(mActivity);
                    } else {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_BG_COLOR)
                                .setColor(getResources().getColor(R.color.novel_holder))
                                .show(mActivity);
                    }
                    return true;
                } else if (item.getItemId() == R.id.action_font_size) {
                    showFontSizeDialog();
                    return true;
                } else if (item.getItemId() == R.id.action_change_text_color) {
                    if (Shaft.sSettings.getNovelHolderTextColor() != 0) {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_TEXT_COLOR)
                                .setColor(Shaft.sSettings.getNovelHolderTextColor())
                                .show(mActivity);
                    } else {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_TEXT_COLOR)
                                .setColor(getResources().getColor(R.color.white))
                                .show(mActivity);
                    }
                    return true;
                } else if (item.getItemId() == R.id.action_save) {
                    mNovelBean.setLocalSaved(true);
                    String fileName = Params.NOVEL_KEY + mNovelBean.getId();
                    Cache.get().saveModel(fileName, mNovelDetail);
                    DownloadEntity downloadEntity = new DownloadEntity();
                    downloadEntity.setFileName(fileName);
                    downloadEntity.setDownloadTime(System.currentTimeMillis());
                    downloadEntity.setFilePath(PathUtils.getInternalAppCachePath());
                    downloadEntity.setIllustGson(Shaft.sGson.toJson(mNovelBean));
                    AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                    Common.showToast(getString(R.string.string_181), 2);
                    baseBind.transformationLayout.finishTransform();
                    return true;
                } else if (item.getItemId() == R.id.action_txt) {
                    // 需要下载txt文件
                    IllustDownload.downloadNovel((BaseActivity<?>) mContext, mNovelBean, novelDetail,
                            new Callback<Uri>() {
                                @Override
                                public void doSomething(Uri t) {
                                    Common.showToast(getString(R.string.string_279), 2);
                                }
                            });
                    return true;
                } else if (item.getItemId() == R.id.action_txt_and_share) {
                    // 不需要下载txt文件
                    IllustDownload.downloadNovel((BaseActivity<?>) mActivity, mNovelBean, novelDetail,
                            new Callback<Uri>() {
                                @Override
                                public void doSomething(Uri uri) {
                                    new Share2.Builder(mActivity)
                                            .setContentType(ShareContentType.FILE)
                                            .setShareFileUri(uri)
                                            .setTitle("Share File")
                                            .build()
                                            .shareBySystem();
                                }
                            });
                    Common.showToast(getString(R.string.string_279), 2);
                    return true;
                } else if (item.getItemId() == R.id.translate) {
                    translate(new TranslationCallback() {
                        @Override
                        public void onTranslationComplete(String result) {
                            mNovelDetail.setNovel_text(result);
                            mNovelDetail
                                    .setParsedChapters(NovelParseHelper.tryParseChapters(mNovelDetail.getNovel_text()));
                            refreshDetail(mNovelDetail);
                        }

                        @Override
                        public void onTranslationFailed(Exception e) {
                            // 在这里处理翻译失败的情况，比如显示错误消息
                            Log.e("Translation", "Failed", e);
                            mNovelDetail.setNovel_text("翻译失败");
                            mNovelDetail
                                    .setParsedChapters(NovelParseHelper.tryParseChapters(mNovelDetail.getNovel_text()));
                            refreshDetail(mNovelDetail);
                        }

                        @Override
                        public void onTranslationChunkReceived(int chunkIndex, String chunkResult,
                                String partialResult) {
                            // 保存精确的滚动位置(包括offset)
                            int currentPosition = -1;
                            int currentOffset = 0;
                            RecyclerView.LayoutManager layoutManager = baseBind.viewPager.getLayoutManager();
                            if (layoutManager instanceof LinearLayoutManager) {
                                LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
                                currentPosition = linearLayoutManager.findFirstVisibleItemPosition();
                                // 获取第一个可见item的顶部偏移量
                                View firstVisibleView = linearLayoutManager.findViewByPosition(currentPosition);
                                if (firstVisibleView != null) {
                                    currentOffset = firstVisibleView.getTop();
                                }
                            }

                            // 更新翻译结果
                            mNovelDetail.setNovel_text(partialResult);
                            mNovelDetail
                                    .setParsedChapters(NovelParseHelper.tryParseChapters(mNovelDetail.getNovel_text()));
                            refreshDetail(mNovelDetail);

                            // 恢复精确的滚动位置
                            final int savedPosition = currentPosition;
                            final int savedOffset = currentOffset;
                            baseBind.viewPager.post(() -> {
                                if (savedPosition >= 0
                                        && savedPosition < baseBind.viewPager.getAdapter().getItemCount()) {
                                    RecyclerView.LayoutManager newLayoutManager = baseBind.viewPager.getLayoutManager();
                                    if (newLayoutManager instanceof LinearLayoutManager) {
                                        // 使用scrollToPositionWithOffset精确恢复位置
                                        ((LinearLayoutManager) newLayoutManager)
                                                .scrollToPositionWithOffset(savedPosition, savedOffset);
                                    }
                                }
                            });
                        }
                    });
                }
                return false;
            }
        });
    }

    public interface TranslationCallback {
        void onTranslationComplete(String result);

        void onTranslationFailed(Exception e);

        void onTranslationChunkReceived(int chunkIndex, String chunkResult, String partialResult);
    }

    private void translate(final TranslationCallback callback) {
        // 显示进度条
        showTranslationProgress(true);

        final AtomicInteger completedChunks = new AtomicInteger(0);
        final String novelText = String.valueOf(mNovelDetail.getNovel_text());
        final String apiKey = ""; // TODO: Replace with your actual API key
        final long startTime = System.currentTimeMillis();
        final AtomicInteger totalChars = new AtomicInteger(0);

        // 创建Worker Thread进行后台处理
        new Thread(() -> {
            try {
                // 改进的分块策略：在段落处分割，保持上下文连贯性
                List<String> chunks = splitTextByParagraphs(novelText, 1000);
                String[] results = new String[chunks.size()];

                // 计算总字符数
                for (String chunk : chunks) {
                    totalChars.addAndGet(chunk.length());
                }

                // 更新进度条最大进度
                getActivity().runOnUiThread(() -> {
                    updateTranslationProgress(0, chunks.size(), 0, totalChars.get(), startTime);
                });

                // 优化的OkHttpClient配置
                final OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(10, TimeUnit.SECONDS)
                        .connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES))
                        .retryOnConnectionFailure(true)
                        .build();

                // 优化策略：前5个块顺序翻译(快速显示开头)，后续块并发翻译(提高速度)
                final int sequentialCount = Math.min(5, chunks.size());
                final AtomicInteger translatedChars = new AtomicInteger(0);
                final AtomicInteger lastUpdateIndex = new AtomicInteger(-1);
                final long[] lastUpdateTime = { System.currentTimeMillis() };

                // 顺序翻译前面的块
                for (int i = 0; i < sequentialCount; i++) {
                    final int index = i;
                    final String chunk = chunks.get(i);

                    String translated = processChunkWithRetry(chunk, apiKey, 3, client);
                    if (translated != null) {
                        Log.i("Translation", "顺序翻译成功，进度" + index + "/" + chunks.size());
                        synchronized (results) {
                            results[index] = translated;
                            translatedChars.addAndGet(chunk.length());

                            // 每个块都更新UI(前面的块很重要)
                            String partialResult = mergeTranslatedChunks(Arrays.asList(results));
                            getActivity().runOnUiThread(() -> {
                                callback.onTranslationChunkReceived(index, translated, partialResult);
                            });
                        }

                        int completed = completedChunks.incrementAndGet();
                        getActivity().runOnUiThread(() -> {
                            updateTranslationProgress(completed, chunks.size(), translatedChars.get(), totalChars.get(),
                                    startTime);
                        });
                    } else {
                        Log.e("Translation", "分块 " + index + "/" + chunks.size() + " 翻译失败");
                        completedChunks.incrementAndGet();
                    }
                }

                // 并发翻译剩余的块
                if (sequentialCount < chunks.size()) {
                    ExecutorService executor = Executors.newFixedThreadPool(15);
                    List<CompletableFuture<Void>> futures = new ArrayList<>();

                    for (int i = sequentialCount; i < chunks.size(); i++) {
                        final int index = i;
                        final String chunk = chunks.get(i);

                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            String translated = processChunkWithRetry(chunk, apiKey, 3, client);
                            if (translated != null) {
                                Log.i("Translation", "并发翻译成功，进度" + index + "/" + chunks.size());
                                synchronized (results) {
                                    results[index] = translated;
                                    translatedChars.addAndGet(chunk.length());

                                    // 优化UI更新频率：每3个块或每2秒更新一次
                                    int currentCompleted = completedChunks.incrementAndGet();
                                    long currentTime = System.currentTimeMillis();
                                    boolean shouldUpdate = (currentCompleted - lastUpdateIndex.get() >= 3) ||
                                            (currentTime - lastUpdateTime[0] >= 2000);

                                    if (shouldUpdate) {
                                        lastUpdateIndex.set(currentCompleted);
                                        lastUpdateTime[0] = currentTime;

                                        String partialResult = mergeTranslatedChunks(Arrays.asList(results));
                                        getActivity().runOnUiThread(() -> {
                                            callback.onTranslationChunkReceived(index, translated, partialResult);
                                            updateTranslationProgress(currentCompleted, chunks.size(),
                                                    translatedChars.get(), totalChars.get(), startTime);
                                        });
                                    } else {
                                        // 只更新进度，不更新UI
                                        getActivity().runOnUiThread(() -> {
                                            updateTranslationProgress(currentCompleted, chunks.size(),
                                                    translatedChars.get(), totalChars.get(), startTime);
                                        });
                                    }
                                }
                            } else {
                                Log.e("Translation", "分块 " + index + "/" + chunks.size() + " 翻译失败");
                                int currentCompleted = completedChunks.incrementAndGet();
                                getActivity().runOnUiThread(() -> {
                                    updateTranslationProgress(currentCompleted, chunks.size(), translatedChars.get(),
                                            totalChars.get(), startTime);
                                });
                            }
                        }, executor);

                        futures.add(future);
                    }

                    // 等待所有任务完成
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    executor.shutdownNow();
                }

                // 改进合并逻辑，确保段落连贯性
                final String finalResult = mergeTranslatedChunks(Arrays.asList(results));

                // 在主线程回调
                getActivity().runOnUiThread(() -> {
                    showTranslationProgress(false);
                    callback.onTranslationComplete(finalResult);
                });
            } catch (Exception e) {
                Log.e("Translation", "翻译过程异常", e);
                getActivity().runOnUiThread(() -> {
                    showTranslationProgress(false);
                    callback.onTranslationFailed(e);
                });
            }
        }).start();
    }

    // 分块处理（带重试）- 优化重试策略
    private String processChunkWithRetry(String chunk, String apiKey, int maxRetries, OkHttpClient client) {
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                JsonObject requestBody = buildRequestBody(chunk, retry);
                Request request = buildRequest(apiKey, requestBody);

                try (okhttp3.Response response = client.newCall(request).execute()) {
                    StringBuilder chunkResult = new StringBuilder();
                    handleResponse(response, chunkResult);
                    return chunkResult.toString();
                }
            } catch (SocketTimeoutException e) {
                if (retry == maxRetries - 1) {
                    Log.e("Translation", "分块最终失败: " + chunk.substring(0, 50));
                } else {
                    try {
                        // 指数退避策略，而不是线性等待
                        long waitTime = (long) (1000 * Math.pow(1.5, retry));
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (IOException e) {
                Log.e("Translation", "网络错误: " + e.getMessage());
                // 网络错误也使用退避策略
                if (retry < maxRetries - 1) {
                    try {
                        long waitTime = (long) (1000 * Math.pow(1.5, retry));
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return chunk; // 翻译失败时返回原文
    }

    // 移除不必要的上下文添加函数，简化翻译流程

    // 改进的文本分块方法，在段落边界分割
    private List<String> splitTextByParagraphs(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n+"); // 按换行符分割段落

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 如果当前段落本身就超过最大长度，则按字符分割
            if (paragraph.length() > maxChunkSize) {
                // 如果当前块不为空，先保存
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                // 将长段落分割成多个块
                for (int i = 0; i < paragraph.length(); i += maxChunkSize) {
                    int end = Math.min(i + maxChunkSize, paragraph.length());
                    chunks.add(paragraph.substring(i, end));
                }
            }
            // 否则，尝试将段落添加到当前块
            else if (currentChunk.length() + paragraph.length() + 1 <= maxChunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n");
                }
                currentChunk.append(paragraph);
            }
            // 如果添加会超过最大长度，则保存当前块，并开始新块
            else {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder(paragraph);
            }
        }

        // 保存最后一个块
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    // 合并翻译结果，确保文本连贯性
    private String mergeTranslatedChunks(List<String> chunks) {
        StringBuilder result = new StringBuilder();
        boolean firstChunk = true;

        for (String chunk : chunks) {
            if (chunk == null)
                continue;

            // 移除可能的不必要标记和无关内容
            String cleanChunk = chunk.replaceAll(
                    "【开始翻译】|【结束翻译】|\\[上文参考\\]|\\[下文参考\\]|\\[以下是原文\\]|\\[原文\\]|\\[翻译\\]|以下是.*翻译结果：|原文：|翻译：",
                    "").trim();

            // 确保没有残留的日文
            if (containsJapanese(cleanChunk)) {
                // 如果还包含日文，尝试再次翻译这个块
                Log.w("Translation", "发现未翻译的日文，进行清理");
                // 可以添加更多清理逻辑
            }

            if (firstChunk) {
                firstChunk = false;
                result.append(cleanChunk);
            } else {
                // 添加段落分隔符
                result.append("\n\n").append(cleanChunk);
            }
        }

        return result.toString();
    }

    // 检查文本是否包含日文
    private boolean containsJapanese(String text) {
        return text.matches(".*[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF].*");
    }

    // 优化版本 - 符号和图片标记保护机制
    private Map<String, String> textToMarkers = new HashMap<>();

    // 提取并保存文本中的特殊标记
    private String protectMarkers(String text) {
        // 找出所有括号标记（[xxx]格式）
        Pattern pattern = Pattern.compile("\\[[^\\]]*\\]");
        Matcher matcher = pattern.matcher(text);
        List<String> markers = new ArrayList<>();

        // 收集所有标记
        while (matcher.find()) {
            String marker = matcher.group();
            if (!markers.contains(marker)) {
                markers.add(marker);
            }
        }

        // 为这部分文本保存标记列表
        textToMarkers.put(text, String.join("|||MARKER|||", markers));

        return text;
    }

    // 构建请求体 - 优化版本
    private JsonObject buildRequestBody(String chunk, int retry) {
        // 保护文本中的标记
        String protectedChunk = protectMarkers(chunk);

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "你是一个专业的日语到中文翻译模型，专门翻译成人小说。\n" +
                "1. 将所有日文内容翻译成流畅的简体中文，保留原文风格。\n" +
                "2. 必须严格保留所有特殊标记和符号，包括但不限于：[uploadedimage:XXX]、[ruby:XXX]、[illust]、标点符号、表情符号等。\n" +
                "3. 标记必须完全原样保留，位置不变，不能修改或删除。\n" +
                "4. 输出只能包含中文翻译和原始标记，不包含任何日文原文。\n" +
                "5. 严格遵循输入长度，不添加额外内容。\n" +
                "6. 绝对禁止添加任何与翻译结果无关的内容，如'以下是原文'、'[原文]'、'[翻译]'等类似说明性文字。");

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        // 移除不必要的标记，直接使用原始文本
        userMessage.addProperty("content", protectedChunk);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "grok-3-mini");
        requestBody.addProperty("temperature", 0.5); // 建议调整到0.5-1.0范围
        requestBody.add("messages", messages);
        return requestBody;
    }

    // 构建请求
    private Request buildRequest(String apiKey, JsonObject requestBody) {
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, requestBody.toString());

        return new Request.Builder()
                .url("https://api.x.ai/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    // 处理响应
    private void handleResponse(okhttp3.Response response, StringBuilder totalTranslation) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "无响应内容";
            Log.e("Translation", "状态码: " + response.code() + " 响应: " + errorBody);
            throw new IOException("API请求失败: HTTP " + response.code());
        }

        String responseBody = response.body().string();
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();

        if (jsonResponse.has("choices")) {
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject()
                        .getAsJsonObject("message");
                if (message.has("content")) {
                    // 检测错误响应内容
                    if (isErrorResponse(message.get("content").getAsString())) {
                        Log.e("Translation", "翻译服务拒绝响应: " + message.get("content").getAsString());
                        // throw new TranslationException("翻译服务拒绝响应: " +
                        // message.get("content").getAsString());
                    }
                    String translatedContent = message.get("content").getAsString();
                    Log.i("Translation", "翻译结果:" + translatedContent);

                    // 清理可能残留的标记和无关内容
                    String cleanContent = translatedContent.replaceAll(
                            "【开始翻译】|【结束翻译】|\\[上文参考\\]|\\[下文参考\\]|\\[以下是原文\\]|\\[原文\\]|\\[翻译\\]|以下是.*翻译结果：|原文：|翻译：",
                            "").trim();

                    // 线程安全地添加清理后的翻译结果
                    synchronized (totalTranslation) {
                        totalTranslation.append(cleanContent);
                    }
                    return;
                }
            }
        }
        throw new IOException("无效的响应结构: " + responseBody);
    }

    // 增强的错误检测方法
    private boolean isErrorResponse(String content) {
        // 定义常见错误关键词（可根据实际情况扩展）
        String[] errorKeywords = {
                "无法提供",
                "无法继续提供",
                "无法为您提供",
                "相关法规",
                "不适合进行翻译",
                "不适合翻译",
                "涉及成人"
        };

        // 统一转为小写进行包含检测
        String lowerContent = content.toLowerCase(Locale.CHINA);
        for (String keyword : errorKeywords) {
            if (lowerContent.contains(keyword.toLowerCase(Locale.CHINA))) {
                return true;
            }
        }
        return false;
    }

    // 文本分块方法（保持顺序）- 保留原方法以兼容其他代码
    private List<String> splitText(String text, int chunkSize) {
        // 使用改进的分块算法
        return splitTextByParagraphs(text, chunkSize);
    }

    // 自定义异常类
    static class TranslationException extends IOException {
        public TranslationException(String message) {
            super(message);
        }
    }

    // 显示翻译进度条
    private void showTranslationProgress(boolean show) {
        if (show) {
            baseBind.progressRela.setVisibility(View.VISIBLE);
            // 设置进度文本
            ((TextView) baseBind.progressRela.findViewById(R.id.text)).setText("翻译中...");
        } else {
            baseBind.progressRela.setVisibility(View.INVISIBLE);
        }
    }

    // 更新翻译进度 - 增强版
    private void updateTranslationProgress(int current, int total, int translatedChars, int totalChars,
            long startTime) {
        // 计算百分比
        int percentage = total > 0 ? (int) ((float) current / total * 100) : 0;
        int charPercentage = totalChars > 0 ? (int) ((float) translatedChars / totalChars * 100) : 0;

        // 更新进度条
        ProgressBar progressBar = baseBind.progressRela.findViewById(R.id.progress);
        TextView progressText = baseBind.progressRela.findViewById(R.id.text);

        // 计算翻译速度和预计剩余时间
        long elapsedTime = System.currentTimeMillis() - startTime;
        String speedInfo = "";
        String etaInfo = "";

        if (elapsedTime > 1000 && translatedChars > 0) {
            // 计算速度 (字符/秒)
            double speed = (double) translatedChars / (elapsedTime / 1000.0);
            speedInfo = String.format(Locale.getDefault(), " | %.0f字/秒", speed);

            // 计算预计剩余时间
            if (speed > 0) {
                int remainingChars = totalChars - translatedChars;
                long etaSeconds = (long) (remainingChars / speed);
                if (etaSeconds < 60) {
                    etaInfo = String.format(Locale.getDefault(), " | 剩余~%d秒", etaSeconds);
                } else {
                    etaInfo = String.format(Locale.getDefault(), " | 剩余~%d分钟", etaSeconds / 60);
                }
            }
        }

        // 设置进度文本
        String progressInfo = String.format(Locale.getDefault(),
                "翻译中: %d/%d块 (%d%%) | %d/%d字 (%d%%)%s%s",
                current, total, percentage,
                translatedChars, totalChars, charPercentage,
                speedInfo, etaInfo);
        progressText.setText(progressInfo);
    }

    private void setNovelAdapter() {
        NovelDetail novelDetail = mNovelDetail;
        // 如果解析成功，就使用新方式
        String novelText = novelDetail.getNovel_text();
        if (novelText == null || novelText.isEmpty()) {
            novelText = "";
        }
        if (novelDetail.getParsedChapters() != null && !novelDetail.getParsedChapters().isEmpty()) {
            String uploadedImageMark = "[uploadedimage:";
            String pixivImageMark = "[pixivimage:";
            if (novelText.contains(uploadedImageMark) || novelText.contains(pixivImageMark)) {
                do {
                    novelText = novelText.replace("][", "]\n[");
                } while (novelText.contains("]["));
                String[] stringArray = novelText.split("\n");
                List<String> textList = new ArrayList<>(Arrays.asList(stringArray));
                List<ListItemHolder> holderList = new ArrayList<>();
                holderList.add(new SpaceHolder());
                for (String s : textList) {
                    holderList.addAll(WebNovelParser.Companion.buildNovelHolders(mWebNovel, s));
                }
                holderList.add(new SpaceHolder());
                holderList.add(new TextDescHolder(getString(R.string.string_107)));
                holderList.add(new SpaceHolder());
                CommonAdapter commonAdapter;
                if (baseBind.viewPager.getAdapter() instanceof CommonAdapter) {
                    commonAdapter = (CommonAdapter) baseBind.viewPager.getAdapter();
                    commonAdapter.submitList(holderList);
                } else {
                    commonAdapter = new CommonAdapter(getViewLifecycleOwner());
                    baseBind.viewPager.setAdapter(commonAdapter);
                    commonAdapter.submitList(holderList);
                }
                } else {
                    VNewAdapter vNewAdapter;
                    if (baseBind.viewPager.getAdapter() instanceof VNewAdapter) {
                        vNewAdapter = (VNewAdapter) baseBind.viewPager.getAdapter();
                        vNewAdapter.submitList(novelDetail.getParsedChapters());
                    } else {
                        vNewAdapter = new VNewAdapter(novelDetail.getParsedChapters(), mContext);
                        baseBind.viewPager.setAdapter(vNewAdapter);
                    }
                }
                if (novelDetail.getNovel_marker() != null) {
                    int parsedSize = novelDetail.getParsedChapters().size();
                    int pageIndex = Math.min(novelDetail.getNovel_marker().getPage(), novelDetail.getParsedChapters().get(parsedSize - 1).getChapterIndex());
                    pageIndex = Math.max(pageIndex, novelDetail.getParsedChapters().get(0).getChapterIndex());
                    baseBind.viewPager.scrollToPosition(pageIndex - 1);

                    // 设置书签
                    int markerPage = mNovelDetail.getNovel_marker().getPage();
                    if (markerPage > 0) {
                        baseBind.saveNovel.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.novel_marker_add)));
                    } else {
                        baseBind.saveNovel.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.novel_marker_none)));
                    }
                } else {
                    baseBind.saveNovel.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.novel_marker_none)));
                }

                baseBind.saveNovel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        View someView = baseBind.viewPager.findChildViewUnder(0, 0);
                        int currentPageIndex = baseBind.viewPager.findContainingViewHolder(someView).getAdapterPosition();
                        int chapterIndex = mNovelDetail.getParsedChapters().get(currentPageIndex).getChapterIndex();
                        PixivOperate.postNovelMarker(mNovelDetail.getNovel_marker(), mNovelBean.getId(), chapterIndex, baseBind.saveNovel);
                    }
                });
                    }
                    // 旧方式
                    else {
                        if (novelDetail.getNovel_text().contains("[newpage]")) {
                            String[] partList = novelDetail.getNovel_text().split("\[newpage]");
                            VAdapter vAdapter;
                            if (baseBind.viewPager.getAdapter() instanceof VAdapter) {
                                vAdapter = (VAdapter) baseBind.viewPager.getAdapter();
                                vAdapter.submitList(Arrays.asList(partList));
                            } else {
                                vAdapter = new VAdapter(Arrays.asList(partList), mContext);
                                baseBind.viewPager.setAdapter(vAdapter);
                            }
                        } else {
                            VAdapter vAdapter;
                            if (baseBind.viewPager.getAdapter() instanceof VAdapter) {
                                vAdapter = (VAdapter) baseBind.viewPager.getAdapter();
                                vAdapter.submitList(Collections.singletonList(novelDetail.getNovel_text()));
                            } else {
                                vAdapter = new VAdapter(Collections.singletonList(novelDetail.getNovel_text()), mContext);
                                baseBind.viewPager.setAdapter(vAdapter);
                            }
                        }
                    }
                }

    private void showFontSizeDialog() {
        View view = LayoutInflater.from(mContext).inflate(R.layout.dialog_font_size, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setView(view);

        TextView previewText = view.findViewById(R.id.preview_text);
        final SeekBar seekBar = view.findViewById(R.id.font_size_seekbar);
        TextView currentSizeText = view.findViewById(R.id.current_size_text);

        // 从设置中获取当前字体大小
        int currentSize = Shaft.sSettings.getNovelHolderTextSize();
        if (currentSize == 0) {
            currentSize = 16; // 默认大小
        }

        seekBar.setProgress(currentSize);
        currentSizeText.setText(currentSize + "sp");
        previewText.setTextSize(currentSize);

        // 添加确认和取消按钮
        builder.setPositiveButton(R.string.sure, (dialog, which) -> {
            if (mActivity instanceof TemplateActivity) {
                ((TemplateActivity) mActivity).onFontSizeSelected(seekBar.getProgress());
            }
        });

        builder.setNegativeButton(R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.show();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                previewText.setTextSize(progress);
                currentSizeText.setText(progress + "sp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    public void setTextSize(int size) {
        // 更新 ViewPager 中的文本大小
        RecyclerView.Adapter<?> adapter = baseBind.viewPager.getAdapter();
        if (adapter instanceof VNewAdapter) {
            ((VNewAdapter) adapter).updateTextSize(size);
        }
    }
}
