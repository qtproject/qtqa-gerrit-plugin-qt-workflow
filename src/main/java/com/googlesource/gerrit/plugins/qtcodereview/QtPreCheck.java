//
// Copyright (C) 2021-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.common.InputWithMessage;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.permissions.LabelPermission;
import com.google.gerrit.server.permissions.LabelPermission.ForUser;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;

@Singleton
public class QtPreCheck
    implements RestModifyView<RevisionResource, InputWithMessage>, UiAction<RevisionResource> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String DEFAULT_TOOLTIP = "Trigger a precheck integration";
  private static final String DEFAULT_TOOLTIP_DISABLED = "Precheck disabled for this project";
  private static final String LABEL_CODE_REVIEW = "Code-Review";
  private static final short LABEL_CODE_REVIEW_VALUE = 2;

  public static class Output {
    transient Change change;

    private Output(Change c) {
      change = c;
    }
  }

  private final PermissionBackend permissionBackend;
  private final BatchUpdate.Factory updateFactory;
  private final QtUtil qtUtil;
  private final String label;
  private final ParameterizedString titlePattern;
  private final ParameterizedString titlePatternDisabled;

  @Inject private PluginConfigFactory pluginCfg;

  @Inject private QtChangeUpdateOp.Factory qtUpdateFactory;

  @Inject
  QtPreCheck(
      PermissionBackend permissionBackend,
      @GerritServerConfig Config cfg,
      BatchUpdate.Factory updateFactory,
      QtUtil qtUtil) {

    this.permissionBackend = permissionBackend;
    this.updateFactory = updateFactory;
    this.qtUtil = qtUtil;
    this.label = "PreCheck";
    this.titlePattern =
        new ParameterizedString(
            MoreObjects.firstNonNull(
                cfg.getString("precheck", null, "precheckTooltip"), DEFAULT_TOOLTIP));
    this.titlePatternDisabled =
        new ParameterizedString(
            MoreObjects.firstNonNull(
                cfg.getString("precheck", null, "precheckTooltip"), DEFAULT_TOOLTIP_DISABLED));
  }

  @Override
  public Response<Output> apply(RevisionResource rsrc, InputWithMessage in)
      throws RestApiException, RepositoryNotFoundException, IOException, PermissionBackendException,
          UpdateException, ConfigInvalidException {
    logger.atInfo().log("precheck request for %s", rsrc.getChange().toString());

    boolean canReview;

    canReview =
        rsrc.permissions()
            .test(
                new LabelPermission.WithValue(
                    ForUser.SELF, LABEL_CODE_REVIEW, LABEL_CODE_REVIEW_VALUE));

    if (!canReview) {
      throw new AuthException(
          String.format(
              "Precheck request from user %s without permission, %s",
              rsrc.getUser().getUserName(), rsrc.getChange().toString()));
    }

    if (!isPreCheckAllowed(rsrc)) {
      throw new AuthException(
          String.format(
              "Precheck request for project not allowed, %s", rsrc.getChange().toString()));
    }

    Change change = rsrc.getChange();
    Output output;
    output = new Output(change);
    if (in.message == null) in.message = "none";
    this.qtUtil.postChangePreCheckEvent(change, rsrc.getPatchSet(), in);

    // Generate user friendly message
    String[] inputs = in.message.split("&");
    StringBuilder msg = new StringBuilder();
    for (String input : inputs) {
      String[] values = input.split(":");
      String property = values[0];
      if (values.length < 2) {
        continue;
      }

      if (msg.length() > 0) {
        msg.append(", ");
      }
      msg.append(property + ": " + values[1]);
    }

    QtChangeUpdateOp op =
        qtUpdateFactory.create(
            null,
            null,
            String.format("Precheck requested with params %s", msg.toString()),
            null,
            QtUtil.TAG_CI,
            null);
    try (BatchUpdate u =
        updateFactory.create(change.getProject(), rsrc.getUser(), TimeUtil.nowTs())) {
      u.addOp(change.getId(), op).execute();
    }
    return Response.ok(output);
  }

  @Override
  public UiAction.Description getDescription(RevisionResource resource) {
    boolean canReview;
    try {
      canReview =
          resource
              .permissions()
              .test(
                  new LabelPermission.WithValue(
                      ForUser.SELF, LABEL_CODE_REVIEW, LABEL_CODE_REVIEW_VALUE));
    } catch (PermissionBackendException e) {
      logger.atInfo().log(e.getMessage());
      return null;
    }

    if (!canReview) {
      return null;
    }

    Change change = resource.getChange();
    if (!change.getStatus().isOpen() || !resource.isCurrent()) {
      return null; // precheck not visible
    }

    boolean enabled = isPreCheckAllowed(resource);

    ObjectId revId = resource.getPatchSet().commitId();
    Map<String, String> params =
        ImmutableMap.of(
            "patchSet", String.valueOf(resource.getPatchSet().number()),
            "branch", change.getDest().shortName(),
            "commit", revId.abbreviate(7).name());
    return new UiAction.Description()
        .setLabel(this.label)
        .setTitle(
            Strings.emptyToNull(
                enabled ? titlePattern.replace(params) : titlePatternDisabled.replace(params)))
        .setVisible(true)
        .setEnabled(enabled);
  }

  private boolean isPreCheckAllowed(RevisionResource resource) {
    String[] disabledProjects =
        pluginCfg
            .getGlobalPluginConfig("gerrit-plugin-qt-workflow")
            .getStringList("precheck", "disabled", "projects");

    return !Arrays.asList(disabledProjects).contains(resource.getProject().get());
  }
}
