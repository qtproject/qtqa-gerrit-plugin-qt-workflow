// Copyright (C) 2018 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;

import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtCodeReviewIT extends LightweightPluginDaemonTest {

    @Test
    public void dummyTest() {

    }

}
