package cn.luckyh.mqx.spring;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Spring Bean定义查询与可选插件类加载边界。
 *
 * @author heng.wang
 * @since 2026-11-10
 */
public interface SpringBeanSource {

    // Spring支持启用后提供Bean定义查询实现
    ExtensionPointName<SpringBeanSource> EP_NAME =
            ExtensionPointName.create("cn.luckyh.mqx.springBeanSource");

    // 模型参与的快照启动后重新查询上下文，不复用旧Profile结果
    ModificationTracker MODEL_USAGE = () -> 0;

    /**
     * 按当前模型查询Bean定义；名称存在时保留全部同名定义，类型由调用端校验。
     * 名称为空时按所需类型查询，多个候选不由适配层选择。
     * 模型中存在但源码不可定位的Bean用null项保留存在性，不能因此退化为类型查询。
     */
    @NotNull List<@Nullable PsiElement> findBeans(
            @NotNull PsiElement context,
            @Nullable PsiType requiredType,
            @Nullable String beanName,
            @NotNull Set<Object> cacheDependencies);
}
