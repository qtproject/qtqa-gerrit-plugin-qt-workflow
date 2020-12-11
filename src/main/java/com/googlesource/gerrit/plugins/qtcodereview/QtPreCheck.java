//
// Copyright (C) 2021 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.permissions.LabelPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.LabelPermission.ForUser;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.UpdateException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import java.io.IOException;
import java.util.Map;

@Singleton
public class QtPreCheck
    implements RestModifyView<RevisionResource, SubmitInput>, UiAction<RevisionResource> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String DEFAULT_TOOLTIP = "Trigger a precheck integration";
  private static final String LABEL_CODE_REVIEW = "Code-Review";
  private static final short LABEL_CODE_REVIEW_VALUE = 2;

  public static class Output {
    transient Change change;

    private Output(Change c) {
      change = c;
    }
  }

  private final PermissionBackend permissionBackend;
  private final ChangeData.Factory changeDataFactory;
  private final QtUtil qtUtil;
  private final String label;
  private final ParameterizedString titlePattern;

  private Change change;

  @Inject
  QtPreCheck(
    PermissionBackend permissionBackend,
    @GerritServerConfig Config cfg,
    ChangeData.Factory changeDataFactory,
    QtUtil qtUtil) {

    this.permissionBackend = permissionBackend;
    this.changeDataFactory = changeDataFactory;
    this.qtUtil = qtUtil;
    this.label = "PreCheck";
    this.titlePattern =
      new ParameterizedString(
        MoreObjects.firstNonNull(
          cfg.getString("precheck", null, "precheckTooltip"), DEFAULT_TOOLTIP));
  }

  @Override
  public Response<Output> apply(RevisionResource rsrc, SubmitInput input)
      throws RestApiException, RepositoryNotFoundException, IOException, PermissionBackendException,
          UpdateException, ConfigInvalidException {
    logger.atInfo().log("qtcodereview: precheck request for %s", rsrc.getChange().toString());
    boolean canReview;

    canReview = rsrc.permissions().test(new LabelPermission.
      WithValue(ForUser.SELF, LABEL_CODE_REVIEW, LABEL_CODE_REVIEW_VALUE));

    if (!canReview) {
      throw new AuthException(String.format("Precheck request from user %s without permission, %s",
        rsrc.getUser().getUserName(), rsrc.getChange().toString()));
    }

    Change change = rsrc.getChange();
    Output output;
    output = new Output(change);
    this.qtUtil.postChangePreCheckEvent(change, rsrc.getPatchSet());
    return Response.ok(output);
  }

  @Override
  public UiAction.Description getDescription(RevisionResource resource) {
    boolean canReview;
    try {
      canReview = resource.permissions().test(new LabelPermission
        .WithValue(ForUser.SELF, LABEL_CODE_REVIEW, LABEL_CODE_REVIEW_VALUE));
    } catch (PermissionBackendException e) {
      logger.atInfo().log(e.getMessage());
      return null;
    }

    if (!canReview) {
      return null;
    }

    Change change = resource.getChange();
    if (!change.getStatus().isOpen()
        || !resource.isCurrent()) {
      return null; // precheck not visible
    }

    ObjectId revId = resource.getPatchSet().commitId();
    Map<String, String> params =
      ImmutableMap.of(
        "patchSet", String.valueOf(resource.getPatchSet().number()),
        "branch", change.getDest().shortName(),
        "commit", revId.abbreviate(7).name());
    return new UiAction.Description()
      .setLabel(this.label)
      .setTitle(Strings.emptyToNull(titlePattern.replace(params)))
      .setVisible(true)
      .setEnabled(true);
  }
}
