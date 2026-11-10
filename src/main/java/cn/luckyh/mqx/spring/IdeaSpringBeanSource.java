package cn.luckyh.mqx.spring;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.spring.CommonSpringModel;
import com.intellij.spring.SpringManager;
import com.intellij.spring.model.SpringBeanPointer;
import com.intellij.spring.model.SpringModelSearchParameters;
import com.intellij.spring.model.utils.SpringModelSearchers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * IDEA Spring模型提供当前上下文的Bean定义。
 *
 * @author heng.wang
 * @since 2026-11-10
 */
public final class IdeaSpringBeanSource implements SpringBeanSource {

    private static final String BEAN = "org.springframework.context.annotation.Bean";

    @Override
    public @NotNull List<@Nullable PsiElement> findBeans(
            @NotNull PsiElement context,
            @Nullable PsiType requiredType,
            @Nullable String beanName,
            @NotNull Set<Object> cacheDependencies) {
        // Bean查询只使用已完成索引的当前模块和文件
        Module module = ModuleUtilCore.findModuleForPsiElement(context);
        PsiFile file = context.getContainingFile();
        if (module == null || file == null || DumbService.isDumb(context.getProject())) {
            return List.of();
        }

        // Spring上下文和Profile变化使端点快照失效
        SpringManager manager = SpringManager.getInstance(context.getProject());
        Collections.addAll(cacheDependencies, manager.getModelsDependencies(module));
        var models = manager.getSpringModelsByFile(file);
        if (models.size() != 1) {
            return List.of();
        }
        CommonSpringModel model = models.iterator().next();

        // 明确名称保留全部同名定义，类型查询包含库中候选以保留歧义
        Collection<SpringBeanPointer<?>> beans;
        if (beanName != null) {
            beans = SpringModelSearchers.findBeans(model, beanName);
        } else if (requiredType != null) {
            beans = SpringModelSearchers.findBeans(model,
                    SpringModelSearchParameters.byType(requiredType).withInheritors().searchInLibraries(true));
        } else {
            return List.of();
        }

        // Java Bean标识提升到真实工厂方法，其它来源保留原始定义
        Set<PsiElement> definitions = new LinkedHashSet<>();
        for (SpringBeanPointer<?> bean : beans) {
            PsiElement element = bean.isValid() ? bean.getPsiElement() : null;
            if (element != null && !(element instanceof PsiMethod)) {
                PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
                if (method != null && method.getAnnotation(BEAN) != null
                        && (element == method.getNameIdentifier()
                        || PsiTreeUtil.isAncestor(method.getModifierList(), element, false))) {
                    element = method;
                }
            }
            definitions.add(element);
        }
        return Collections.unmodifiableList(new ArrayList<>(definitions));
    }
}
