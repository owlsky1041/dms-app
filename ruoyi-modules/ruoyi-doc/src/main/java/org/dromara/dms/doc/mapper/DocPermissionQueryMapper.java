package org.dromara.dms.doc.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.dms.doc.domain.DocFile;

import java.util.Collection;
import java.util.List;

/**
 * 权限过滤查询 Mapper（SQL 级过滤，保证分页与 total 正确）
 *
 * <p>判定规则与 {@code PermissionServiceImpl.computeUserFlags} 保持一致：
 * <ul>
 *   <li>所有者（folder.owner_id / file.creator_id == 当前用户）→ 完全控制</li>
 *   <li>否则取「资源自身 + 父链」权限行的位掩码并集</li>
 *   <li>主体 = user(本人) + role(全部角色) + dept(本部门及祖先链)</li>
 *   <li>列表/搜索要求 VISIBLE 位（1）</li>
 * </ul>
 *
 * @author DMS
 */
@Mapper
public interface DocPermissionQueryMapper {

    /**
     * 当前用户可见（VISIBLE）的全部文件夹 ID
     *
     * <p>一次查询得出，供列表/搜索过滤复用，避免逐条 N+1 判定。
     */
    List<Long> selectReadableFolderIds(@Param("userId") Long userId,
                                       @Param("roleIds") Collection<Long> roleIds,
                                       @Param("deptIds") Collection<Long> deptIds);

    /**
     * 分页查询某文件夹下当前用户可见的文件（SQL 级过滤，total 准确）
     */
    IPage<DocFile> pageReadableFiles(IPage<DocFile> page,
                                     @Param("folderId") Long folderId,
                                     @Param("userId") Long userId,
                                     @Param("readableFolderIds") Collection<Long> readableFolderIds,
                                     @Param("roleIds") Collection<Long> roleIds,
                                     @Param("deptIds") Collection<Long> deptIds);

    /**
     * 全文搜索当前用户可见的文件（文件名 + 描述 + 提取正文）
     */
    List<DocFile> searchReadableFiles(@Param("keyword") String keyword,
                                      @Param("limit") int limit,
                                      @Param("userId") Long userId,
                                      @Param("readableFolderIds") Collection<Long> readableFolderIds,
                                      @Param("roleIds") Collection<Long> roleIds,
                                      @Param("deptIds") Collection<Long> deptIds);
}
