package cn.luckyh.mqx.parser;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.search.GlobalSearchScope;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MqEndpointParser {

    @NotNull List<MqEndpoint> collectEndpoints(
            @NotNull Project project,
            @NotNull GlobalSearchScope projectScope,
            @NotNull Set<Object> cacheDependencies);

    @NotNull Optional<MqEndpoint> parseAnchor(
            @NotNull PsiElement anchor,
            @NotNull Set<Object> cacheDependencies);

    static MqEndpoint endpoint(
            PsiElement anchor,
            MqEndpoint.SystemType system,
            MqEndpoint.Role role,
            MqDestination destination,
            MqEndpoint.ResolutionState resolutionState,
            String resolutionMessage) {
        PsiFile file = anchor.getContainingFile();
        Module module = ModuleUtilCore.findModuleForPsiElement(anchor);
        PsiClass containingClass = anchor instanceof PsiMember member
                ? member.getContainingClass()
                : com.intellij.psi.util.PsiTreeUtil.getParentOfType(anchor, PsiClass.class);
        PsiMember member = com.intellij.psi.util.PsiTreeUtil.getParentOfType(anchor, PsiMember.class);
        String fileUrl = file != null && file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : "";
        String endpointId = system + ":" + role + ":" + fileUrl + ":" + anchor.getTextOffset()
                + ":" + destination.summary();
        // 逻辑变动: 匿名类与无名成员的展示名称在端点边界归一为空串-20260817-1952-01
        String className = containingClass != null ? containingClass.getName() : null;
        String memberName = member != null ? member.getName() : null;
        return new MqEndpoint(
                endpointId,
                system,
                role,
                destination,
                resolutionState,
                resolutionMessage,
                SmartPointerManager.getInstance(anchor.getProject()).createSmartPsiElementPointer(anchor),
                module != null ? module.getName() : "",
                file != null ? file.getName() : "",
                className != null ? className : "",
                memberName != null ? memberName : "");
    }

    static PsiAnnotation annotation(PsiModifierListOwner owner, String qualifiedName) {
        return owner.getAnnotation(qualifiedName);
    }

    static List<PsiAnnotationMemberValue> annotationValues(PsiAnnotation annotation, String attributeName) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attributeName);
        if (value == null) {
            return List.of();
        }
        if (value instanceof PsiArrayInitializerMemberValue array) {
            return List.of(array.getInitializers());
        }
        return List.of(value);
    }

    static boolean isStringType(com.intellij.psi.PsiType type) {
        String canonical = type.getCanonicalText();
        return com.intellij.psi.CommonClassNames.JAVA_LANG_STRING.equals(canonical)
                || "String".equals(canonical)
                || "String".equals(type.getPresentableText());
    }
}
