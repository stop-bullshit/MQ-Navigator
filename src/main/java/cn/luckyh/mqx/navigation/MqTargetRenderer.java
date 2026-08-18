package cn.luckyh.mqx.navigation;

import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import cn.luckyh.mqx.model.MqEndpoint;

import javax.swing.Icon;
import java.util.List;

public final class MqTargetRenderer extends PsiTargetPresentationRenderer<PsiElement> {

    private final List<MqEndpoint> endpoints;

    public MqTargetRenderer(List<MqEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    @Override
    public String getElementText(PsiElement element) {
        MqEndpoint endpoint = endpointFor(element);
        if (endpoint == null) {
            return element.getText();
        }
        return "[" + endpoint.system() + "] " + endpoint.moduleName() + " / " + endpoint.fileName()
                + " : " + endpoint.className() + "#" + endpoint.memberName()
                + " - " + endpoint.destination().summary();
    }

    @Override
    public String getContainerText(PsiElement element) {
        return null;
    }

    @Override
    protected Icon getIcon(PsiElement element) {
        return element.getIcon(0);
    }

    private MqEndpoint endpointFor(PsiElement element) {
        for (MqEndpoint endpoint : endpoints) {
            PsiElement candidate = endpoint.sourcePointer().getElement();
            if (candidate != null && PsiManager.getInstance(element.getProject())
                    .areElementsEquivalent(candidate, element)) {
                return endpoint;
            }
        }
        return null;
    }
}
