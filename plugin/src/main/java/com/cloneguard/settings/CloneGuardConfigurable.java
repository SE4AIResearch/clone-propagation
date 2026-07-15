package com.cloneguard.settings;

import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Registers CloneGuard's server URL as a configurable entry under
 * Settings/Preferences, per the professor's recommendation (4.2).
 *
 * NOTE: this class alone does not make the panel appear in Settings —
 * it must also be registered as an applicationConfigurable extension
 * point in plugin.xml. That file wasn't available in this session, so
 * it could not be edited directly; the required addition is:
 *
 * <pre>{@code
 * <extensions defaultExtensionNs="com.intellij">
 *     <applicationConfigurable
 *         parentId="tools"
 *         instance="com.cloneguard.settings.CloneGuardConfigurable"
 *         id="com.cloneguard.settings.CloneGuardConfigurable"
 *         displayName="CloneGuard"/>
 * </extensions>
 * }</pre>
 *
 * Add this inside the existing {@code <extensions>} block in plugin.xml
 * (or a new one, if none exists yet) for the panel to appear under
 * Settings/Preferences → Tools → CloneGuard.
 */
public class CloneGuardConfigurable implements com.intellij.openapi.options.Configurable {

    private JTextField serverUrlField;
    private JPanel panel;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "CloneGuard";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(new JLabel("CloneGuard server URL:"), gbc);

        serverUrlField = new JTextField(30);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        form.add(serverUrlField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel hint = new JLabel(
                "<html><i>Point this at a shared team or cloud-hosted CloneGuard server. "
                        + "Defaults to " + CloneGuardSettings.DEFAULT_SERVER_URL
                        + " if left blank.</i></html>");
        hint.setForeground(Color.GRAY);
        form.add(hint, gbc);

        panel.add(form, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        String current = CloneGuardSettings.getInstance().getServerUrl();
        String edited = serverUrlField.getText().trim();
        String effectiveEdited = edited.isEmpty() ? CloneGuardSettings.DEFAULT_SERVER_URL : edited;
        return !current.equals(effectiveEdited);
    }

    @Override
    public void apply() {
        CloneGuardSettings.getInstance().setServerUrl(serverUrlField.getText().trim());
    }

    @Override
    public void reset() {
        serverUrlField.setText(CloneGuardSettings.getInstance().getServerUrl());
    }
}