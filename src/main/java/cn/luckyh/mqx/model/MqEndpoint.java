package cn.luckyh.mqx.model;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record MqEndpoint(
        @NotNull String endpointId,
        @NotNull SystemType system,
        @NotNull Role role,
        @NotNull MqDestination destination,
        @NotNull ResolutionState resolutionState,
        @Nullable String resolutionMessage,
        @NotNull SmartPsiElementPointer<PsiElement> sourcePointer,
        @NotNull String moduleName,
        @NotNull String fileName,
        @NotNull String className,
        @NotNull String memberName) {

    public MqEndpoint {
        Objects.requireNonNull(endpointId);
        Objects.requireNonNull(system);
        Objects.requireNonNull(role);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(resolutionState);
        Objects.requireNonNull(sourcePointer);
        moduleName = Objects.requireNonNullElse(moduleName, "");
        fileName = Objects.requireNonNullElse(fileName, "");
        className = Objects.requireNonNullElse(className, "");
        memberName = Objects.requireNonNullElse(memberName, "");
    }

    public boolean isResolved() {
        return resolutionState == ResolutionState.STATIC
                || resolutionState == ResolutionState.CONFIG_RESOLVED;
    }

    public enum SystemType {
        RABBITMQ,
        KAFKA,
        ROCKETMQ
    }

    public enum Role {
        PRODUCER,
        CONSUMER,
        DECLARATION,
        BINDING,
        EXCHANGE_DECLARATION
    }

    public enum ResolutionState {
        STATIC,
        CONFIG_RESOLVED,
        DYNAMIC,
        INVALID
    }
}
