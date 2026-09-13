package org.dromara.dms.doc.service;

/**
 * 同名项命名服务
 *
 * <p>对应需求「单个文件夹内：同名文件自动重命名为 {@code file (1).ext}；
 * 同名文件夹合并内容（提示用户）」。
 *
 * <p>上传、秒传引用、复制粘贴等「系统自动落库」的场景调用
 * {@link #uniqueFileName}/{@link #uniqueFolderName} 自动避让；
 * 而用户显式输入名字的重命名则应当报错而不是悄悄改名，用
 * {@link #fileNameExists}/{@link #folderNameExists} 判断后给出提示。
 *
 * @author DMS
 */
public interface DocNameService {

    /**
     * 在指定文件夹下生成不冲突的文件名
     *
     * <p>{@code 报告.pdf} 已存在时依次尝试 {@code 报告 (1).pdf}、{@code 报告 (2).pdf} …
     *
     * @param folderId    目标文件夹 ID
     * @param desiredName 期望的文件名
     * @return 实际可用的文件名（无冲突时原样返回）
     */
    String uniqueFileName(Long folderId, String desiredName);

    /**
     * 在指定文件夹下生成不冲突的文件夹名
     *
     * @param parentId    父文件夹 ID
     * @param desiredName 期望的文件夹名
     * @return 实际可用的文件夹名（无冲突时原样返回）
     */
    String uniqueFolderName(Long parentId, String desiredName);

    /**
     * 该文件夹下是否已存在同名文件
     */
    boolean fileNameExists(Long folderId, String name);

    /**
     * 该父文件夹下是否已存在同名子文件夹
     */
    boolean folderNameExists(Long parentId, String name);

    /**
     * 拆分文件名中的「主名」与「扩展名（含点）」
     *
     * <p>以最后一个点为界；没有点、或以点开头的隐藏文件（{@code .gitignore}）
     * 视为无扩展名，这样 {@code .gitignore} 的副本是 {@code .gitignore (1)} 而不是
     * {@code  (1).gitignore}。
     *
     * @param name 文件名
     * @return 长度为 2 的数组：[主名, 扩展名含点]
     */
    static String[] splitName(String name) {
        if (name == null) {
            return new String[]{"", ""};
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return new String[]{name, ""};
        }
        return new String[]{name.substring(0, dot), name.substring(dot)};
    }
}
