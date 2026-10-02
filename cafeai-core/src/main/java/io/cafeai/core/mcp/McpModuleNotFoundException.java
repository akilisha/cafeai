package io.cafeai.core.mcp;

/** Thrown by {@code app.mcp()} when {@code cafeai-mcp} is not on the classpath. */
public final class McpModuleNotFoundException extends RuntimeException {

    public McpModuleNotFoundException() {
        super("app.mcp() requires the cafeai-mcp module. Add the following dependency:\n\n"
              + "  Gradle: implementation 'com.akilisha.oss:cafeai-mcp'\n"
              + "  Maven:  <artifactId>cafeai-mcp</artifactId>");
    }
}
