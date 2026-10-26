package cn.luckyh.mqx.navigation;

import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import cn.luckyh.mqx.model.MqEndpoint;

import javax.swing.Icon;
import java.util.List;

public final class MqTargetRenderer extends PsiTargetPresentationRenderer<PsiElement> {

    private final List<MqEndpoint> endpoints;
    private final String sharedQueuePrefix;

    public MqTargetRenderer(List<MqEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
        String commonQueue = null;
        for (MqEndpoint endpoint : endpoints) {
            String queue = endpoint.destination().queue();
            if (queue == null || queue.isEmpty()) {
                commonQueue = "";
                break;
            }
            if (commonQueue == null) {
                commonQueue = queue;
                continue;
            }
            int length = Math.min(commonQueue.length(), queue.length());
            int index = 0;
            while (index < length && commonQueue.charAt(index) == queue.charAt(index)) {
                index++;
            }
            commonQueue = commonQueue.substring(0, index);
        }
        int separator = commonQueue == null ? -1 : commonQueue.lastIndexOf('.');
        sharedQueuePrefix = separator < 0 ? "" : commonQueue.substring(0, separator + 1);
    }

    @Override
    public String getElementText(PsiElement element) {
        MqEndpoint endpoint = endpointFor(element);
        if (endpoint == null) {
            return element.getText();
        }
        String className = endpoint.className();
        if (className.isEmpty()) {
            className = endpoint.fileName();
            if (className.endsWith(".java")) {
                className = className.substring(0, className.length() - ".java".length());
            }
        }
        String memberName = endpoint.memberName();
        return memberName.isEmpty() || memberName.equals(className)
                ? className : className + "#" + memberName;
    }

    @Override
    public String getContainerText(PsiElement element) {
        MqEndpoint endpoint = endpointFor(element);
        if (endpoint == null) {
            return null;
        }
        String module = endpoint.moduleName();
        if (module.endsWith(".main") || module.endsWith(".test")) {
            module = module.substring(0, module.lastIndexOf('.'));
        }
        int separator = module.lastIndexOf('.');
        if (separator >= 0) {
            module = module.substring(separator + 1);
        }
        String queue = endpoint.destination().queue();
        // 逻辑变动: 目标弹窗突出类方法并折叠重复队列前缀-20261023-1145-01
        String destination = queue == null || queue.isEmpty()
                ? endpoint.destination().summary()
                : "queue " + (sharedQueuePrefix.isEmpty() ? queue : queue.substring(sharedQueuePrefix.length()));
        return module.isEmpty() ? destination : module + " · " + destination;
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
