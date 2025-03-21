package com.devoxx.genie.service.mcp;

import com.devoxx.genie.ui.settings.DevoxxGenieStateService;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for working with MCP servers
 */
@Slf4j
public class MCPService {

    /**
     * Check if MCP is enabled in the settings
     * 
     * @return true if MCP is enabled, false otherwise
     */
    public static boolean isMCPEnabled() {
        return DevoxxGenieStateService.getInstance().getMcpEnabled();
    }

    /**
     * Check if MCP debug logs are enabled
     * 
     * @return true if debug logs are enabled, false otherwise
     */
    public static boolean isDebugLogsEnabled() {
        DevoxxGenieStateService stateService = DevoxxGenieStateService.getInstance();
        return isMCPEnabled() && stateService.getMcpDebugLogsEnabled();
    }
    
    /**
     * Log a debug message if debug logs are enabled
     * 
     * @param message The message to log
     */
    public static void logDebug(String message) {
        if (isDebugLogsEnabled()) {
            log.info("[MCP Debug] {}", message);
        }
    }
}
