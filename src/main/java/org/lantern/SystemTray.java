package org.lantern;

import java.util.Map;

/**
 * Interface for system tray implementations.
 */
public interface SystemTray {

    void createTray();

    void addUpdate(Map<String, Object> updateData);

    boolean isActive();

}
