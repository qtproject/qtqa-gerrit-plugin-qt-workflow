//
// Copyright (C) 2023-24 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import org.kohsuke.args4j.Option;

/**
 * A command to upload ci server queue information, which can be shown on Gerrit UI Json format of
 * the queue info: [ { name: string, running: number, queue: number, load: number }, ... ]
 *
 *  name:       Name of the hardware pool
 *  running:    Number of running jobs
 *  queue:      Number of jobs waiting in queue
 *  load:       Enum value: 0 = normal load, 1 = high load, 2 = very high load
 *
 *  NOTE: Current json parser in use doesn't accept any whitespaces in the json string.
 *
 * <p>For example$ ssh -p 29418 localhost gerrit-plugin-qt-workflow ci-status-update -i "jsonstring"
 */
@CommandMetaData(
    name = "ci-status-update",
    description = "Upload CI server queue info to Gerrit server.")
class QtCommandUpdateCiStatus extends SshCommand {

  @Inject private PermissionBackend permissionBackend;

  @Inject private QtCiStatusStorage ciStatusStorage;

  @Inject @PluginName private String pluginName;

  @Option(
      name = "--queueinfo",
      aliases = {"-i"},
      required = true,
      usage = "queue info of ci servers")
  private String queue_json;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  protected void run() throws UnloggedFailure {
    logger.atInfo().log("ci-status-update -i %s", queue_json);

    if (permissionBackend
        .currentUser()
        .testOrFalse(
            new PluginPermission(pluginName, QtCiStatusUpdateCapability.CI_STATUS_UPDATE))) {
      String statusMessage = QtCiStatusStorage.setData(queue_json);
      if (statusMessage != null) throw die(statusMessage);
    } else throw die("not permitted");
  }
}
