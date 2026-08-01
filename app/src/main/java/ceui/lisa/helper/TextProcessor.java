package ceui.lisa.helper;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    // 符号和图片标记保护机制
    private Map<String, String> textToMarkers = new HashMap<>();

    /**
     * 改进的文本分块方法，在段落边界分割
     * @param text 要分块的文本
     * @param maxChunkSize 每块的最大大小
     * @return 分块后的文本列表
     */
    public List<String> splitTextByParagraphs(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n+"); // 按换行符分割段落

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 如果当前段落本身就超过最大长度，则智能分割
            if (paragraph.length() > maxChunkSize) {
                // 如果当前块不为空，先保存
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                // 使用智能分割方法
                chunks.addAll(splitLongParagraph(paragraph, maxChunkSize));
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

    /**
     * 智能分割长段落，优先在句子边界分割
     * 识别日文标点：。！？」』）】、英文标点：.!?")]等
     * @param paragraph 要分割的段落
     * @param maxChunkSize 每块的最大大小
     * @return 分割后的文本列表
     */
    private List<String> splitLongParagraph(String paragraph, int maxChunkSize) {
        List<String> result = new ArrayList<>();

        // 定义句子结束标记（日文和英文）
        String sentenceEnders = "。！？」』）】.!?\")]";

        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + maxChunkSize, paragraph.length());

            // 如果不是最后，尝试找到最近的句子结束符
            if (end < paragraph.length()) {
                // 向前搜索句子结束符（最多回退200个字符）
                boolean foundBoundary = false;
                int searchStart = Math.max(end - 200, start);

                for (int i = end - 1; i >= searchStart; i--) {
                    if (sentenceEnders.indexOf(paragraph.charAt(i)) >= 0) {
                        // 找到句子结束符，包含该符号
                        end = i + 1;
                        foundBoundary = true;
                        break;
                    }
                }

                // 如果没找到句子边界，尝试找空格或逗号
                if (!foundBoundary) {
                    String softBoundaries = " 、，,\u3000"; // 包括全角空格
                    for (int i = end - 1; i >= searchStart; i--) {
                        if (softBoundaries.indexOf(paragraph.charAt(i)) >= 0) {
                            end = i + 1;
                            foundBoundary = true;
                            break;
                        }
                    }
                }

                // 如果还是没找到，就在原位置切割（避免死循环）
                if (!foundBoundary && end == start + maxChunkSize) {
                    Log.w("TextProcessor", "Could not find sentence boundary, splitting at character limit");
                }
            }

            String chunk = paragraph.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            start = end;
        }

        return result;
    }

    /**
     * 合并翻译结果，确保文本连贯性
     * @param chunks 翻译后的文本块列表
     * @return 合并后的完整文本
     */
    public String mergeTranslatedChunks(List<String> chunks) {
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
                Log.w("TextProcessor", "发现未翻译的日文，进行清理");
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

    /**
     * 检查文本是否包含日文
     * @param text 要检查的文本
     * @return 是否包含日文
     */
    public boolean containsJapanese(String text) {
        return text.matches(".*[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF].*");
    }

    /**
     * 提取并保存文本中的特殊标记
     * @param text 要处理的文本
     * @return 处理后的文本
     */
    public String protectMarkers(String text) {
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

    /**
     * 文本分块方法（保持顺序）- 保留原方法以兼容其他代码
     * @param text 要分块的文本
     * @param chunkSize 每块的大小
     * @return 分块后的文本列表
     */
    public List<String> splitText(String text, int chunkSize) {
        // 使用改进的分块算法
        return splitTextByParagraphs(text, chunkSize);
    }

    /**
     * 获取文本对应的标记
     * @param text 文本
     * @return 标记字符串
     */
    public String getMarkersForText(String text) {
        return textToMarkers.get(text);
    }

    /**
     * 清除标记映射，释放内存
     */
    public void clearMarkers() {
        textToMarkers.clear();
    }
}
