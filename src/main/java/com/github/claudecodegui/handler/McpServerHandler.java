package com.github.claudecodegui.handler;

import com.github.claudecodegui.ClaudeSDKBridge;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import javax.swing.*;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理消息处理器
 */
public class McpServerHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(McpServerHandler.class);

    private static final String[] SUPPORTED_TYPES = {
        "get_mcp_servers",
        "get_mcp_tools",
        "add_mcp_server",
        "update_mcp_server",
        "delete_mcp_server",
        "validate_mcp_server"
    };

    private final ClaudeSDKBridge sdkBridge = new ClaudeSDKBridge();

    public McpServerHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_mcp_servers":
                handleGetMcpServers();
                return true;
            case "get_mcp_tools":
                handleGetMcpTools(content);
                return true;
            case "add_mcp_server":
                handleAddMcpServer(content);
                return true;
            case "update_mcp_server":
                handleUpdateMcpServer(content);
                return true;
            case "delete_mcp_server":
                handleDeleteMcpServer(content);
                return true;
            case "validate_mcp_server":
                handleValidateMcpServer(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * 获取所有 MCP 服务器
     */
    private void handleGetMcpServers() {
        try {
            List<JsonObject> servers = context.getSettingsService().getMcpServers();
            Gson gson = new Gson();
            String serversJson = gson.toJson(servers);

            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.updateMcpServers", escapeJs(serversJson));
            });
        } catch (Exception e) {
            LOG.error("[McpServerHandler] Failed to get MCP servers: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 MCP 服务器的工具列表
     * 通过 Claude SDK 初始化获取所有 MCP 服务器及其工具信息
     */
    private void handleGetMcpTools(String content) {
        try {
            Gson gson = new Gson();
            JsonObject json = content != null && !content.isEmpty()
                ? gson.fromJson(content, JsonObject.class)
                : new JsonObject();
            String cwd = json.has("cwd") ? json.get("cwd").getAsString() : null;

            LOG.info("[McpServerHandler] Getting MCP tools, cwd=" + cwd);

            // 异步获取 MCP 工具信息
            sdkBridge.getMcpTools(cwd).thenAccept(mcpServersWithTools -> {
                try {
                    LOG.info("[McpServerHandler] SDK returned " + mcpServersWithTools.size() + " MCP servers");

                    // 获取配置文件中的服务器列表
                    List<JsonObject> configServers = context.getSettingsService().getMcpServers();
                    LOG.info("[McpServerHandler] Config has " + configServers.size() + " servers");

                    // 将工具信息合并到配置服务器中
                    for (JsonObject configServer : configServers) {
                        String serverId = configServer.has("id") ? configServer.get("id").getAsString() : "";
                        String serverName = configServer.has("name")
                            ? configServer.get("name").getAsString()
                            : serverId;

                        LOG.info("[McpServerHandler] Looking for match: id=" + serverId + ", name=" + serverName);

                        // 在 SDK 返回的结果中查找匹配的服务器
                        boolean matched = false;
                        for (JsonObject sdkServer : mcpServersWithTools) {
                            String sdkServerName = sdkServer.has("name")
                                ? sdkServer.get("name").getAsString()
                                : "";

                            LOG.info("[McpServerHandler] Comparing with SDK server: " + sdkServerName);

                            // 使用灵活的匹配策略
                            if (matchServerNames(sdkServerName, serverId, serverName)) {
                                matched = true;
                                // 添加状态信息
                                if (sdkServer.has("status")) {
                                    configServer.addProperty("status", sdkServer.get("status").getAsString());
                                }
                                // 添加工具列表
                                if (sdkServer.has("tools")) {
                                    configServer.add("tools", sdkServer.get("tools"));
                                    int toolCount = sdkServer.get("tools").getAsJsonArray().size();
                                    LOG.info("[McpServerHandler] Matched! Added " + toolCount + " tools");
                                }
                                break;
                            }
                        }
                        if (!matched) {
                            LOG.info("[McpServerHandler] No match found for server: " + serverId);
                        }
                    }

                    String serversJson = gson.toJson(configServers);
                    LOG.info("[McpServerHandler] MCP tools merged, servers count=" + configServers.size());

                    ApplicationManager.getApplication().invokeLater(() -> {
                        callJavaScript("window.updateMcpServersWithTools", escapeJs(serversJson));
                    });
                } catch (Exception e) {
                    LOG.error("[McpServerHandler] Failed to merge MCP tools: " + e.getMessage(), e);
                }
            }).exceptionally(e -> {
                LOG.error("[McpServerHandler] Failed to get MCP tools: " + e.getMessage(), e);
                return null;
            });
        } catch (Exception e) {
            LOG.error("[McpServerHandler] Failed to parse get_mcp_tools request: " + e.getMessage(), e);
        }
    }

    /**
     * 规范化服务器名称用于匹配
     * 移除连字符、下划线、空格，并转换为小写
     */
    private String normalizeServerName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return name.toLowerCase().replaceAll("[-_\\s]", "");
    }

    /**
     * 灵活匹配服务器名称
     * 支持精确匹配、忽略大小写匹配、规范化匹配和部分匹配
     */
    private boolean matchServerNames(String sdkName, String configId, String configName) {
        if (sdkName == null || sdkName.isEmpty()) {
            return false;
        }

        // 1. 精确匹配
        if (sdkName.equals(configId) || sdkName.equals(configName)) {
            LOG.info("[McpServerHandler] Exact match found");
            return true;
        }

        // 2. 忽略大小写匹配
        if (sdkName.equalsIgnoreCase(configId) || sdkName.equalsIgnoreCase(configName)) {
            LOG.info("[McpServerHandler] Case-insensitive match found");
            return true;
        }

        // 3. 规范化名称匹配
        String normalizedSdk = normalizeServerName(sdkName);
        String normalizedId = normalizeServerName(configId);
        String normalizedName = normalizeServerName(configName);

        if (normalizedSdk.equals(normalizedId) || normalizedSdk.equals(normalizedName)) {
            LOG.info("[McpServerHandler] Normalized match found: " + normalizedSdk);
            return true;
        }

        // 4. 部分匹配（处理名称前缀/后缀差异）
        if (!normalizedSdk.isEmpty() && !normalizedId.isEmpty()) {
            if (normalizedSdk.contains(normalizedId) || normalizedId.contains(normalizedSdk)) {
                LOG.info("[McpServerHandler] Partial match found between: " + normalizedSdk + " and " + normalizedId);
                return true;
            }
        }

        return false;
    }

    /**
     * 添加 MCP 服务器
     */
    private void handleAddMcpServer(String content) {
        try {
            Gson gson = new Gson();
            JsonObject server = gson.fromJson(content, JsonObject.class);

            context.getSettingsService().upsertMcpServer(server);

            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.mcpServerAdded", escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[McpServerHandler] Failed to add MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.showError", escapeJs("添加 MCP 服务器失败: " + e.getMessage()));
            });
        }
    }

    /**
     * 更新 MCP 服务器
     */
    private void handleUpdateMcpServer(String content) {
        try {
            Gson gson = new Gson();
            JsonObject server = gson.fromJson(content, JsonObject.class);

            context.getSettingsService().upsertMcpServer(server);

            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.mcpServerUpdated", escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[McpServerHandler] Failed to update MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.showError", escapeJs("更新 MCP 服务器失败: " + e.getMessage()));
            });
        }
    }

    /**
     * 删除 MCP 服务器
     */
    private void handleDeleteMcpServer(String content) {
        try {
            Gson gson = new Gson();
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String serverId = json.get("id").getAsString();

            boolean success = context.getSettingsService().deleteMcpServer(serverId);

            if (success) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.mcpServerDeleted", escapeJs(serverId));
                    handleGetMcpServers();
                });
            } else {
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.showError", escapeJs("删除 MCP 服务器失败: 服务器不存在"));
                });
            }
        } catch (Exception e) {
            LOG.error("[McpServerHandler] Failed to delete MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.showError", escapeJs("删除 MCP 服务器失败: " + e.getMessage()));
            });
        }
    }

    /**
     * 验证 MCP 服务器配置
     */
    private void handleValidateMcpServer(String content) {
        try {
            Gson gson = new Gson();
            JsonObject server = gson.fromJson(content, JsonObject.class);

            Map<String, Object> validation = context.getSettingsService().validateMcpServer(server);
            String validationJson = gson.toJson(validation);

            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.mcpServerValidated", escapeJs(validationJson));
            });
        } catch (Exception e) {
            LOG.error("[McpServerHandler] Failed to validate MCP server: " + e.getMessage(), e);
        }
    }
}
