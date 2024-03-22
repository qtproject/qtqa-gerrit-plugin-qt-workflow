//
// Copyright (C) 2021-24 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.CHANGE_MODIFICATION;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.permissions.AbstractLabelPermission.ForUser;
import com.google.gerrit.server.permissions.LabelPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;

@Singleton
public class QtPreCheck
    implements RestModifyView<RevisionResource, QtPrecheckMessage>, UiAction<RevisionResource> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String DEFAULT_TOOLTIP = "Trigger a precheck integration";
  private static final String DEFAULT_TOOLTIP_DISABLED = "Precheck disabled for this project";
  private static final String LABEL_CODE_REVIEW = "Code-Review";
  private static final short LABEL_CODE_REVIEW_VALUE = 2;

  private String[] disabledProjects;
  private ArrayList<AccountGroup.UUID> allowedUserGroups;

  public static class Output {
    transient Change change;

    private Output(Change c) {
      change = c;
    }
  }

  private final PermissionBackend permissionBackend;
  private final GroupCache groupCache;
  private final BatchUpdate.Factory updateFactory;
  private final QtUtil qtUtil;
  private final String label;
  private final ParameterizedString titlePattern;
  private final ParameterizedString titlePatternDisabled;

  @Inject private PluginConfigFactory pluginCfg;

  @Inject private QtChangeUpdateOp.Factory qtUpdateFactory;

  @Inject
  QtPreCheck(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      PermissionBackend permissionBackend,
      GroupCache groupCache,
      BatchUpdate.Factory updateFactory,
      QtUtil qtUtil) {

    Config pluginCfg = cfgFactory.getGlobalPluginConfig(pluginName);

    this.permissionBackend = permissionBackend;
    this.groupCache = groupCache;
    this.updateFactory = updateFactory;
    this.qtUtil = qtUtil;
    this.label = "PreCheck";
    this.titlePattern =
        new ParameterizedString(
            MoreObjects.firstNonNull(
                pluginCfg.getString("precheck", null, "precheckTooltip"), DEFAULT_TOOLTIP));
    this.titlePatternDisabled =
        new ParameterizedString(
            MoreObjects.firstNonNull(
                pluginCfg.getString("precheck", null, "precheckTooltip"),
                DEFAULT_TOOLTIP_DISABLED));

    disabledProjects = pluginCfg.getStringList("precheck", "disabled", "projects");
    String[] allowedUserGroupNames = pluginCfg.getStringList("precheck", "allowed", "groups");

    allowedUserGroups = new ArrayList<AccountGroup.UUID>();
    for (String groupName : allowedUserGroupNames) {
      InternalGroup group = groupCache.get(AccountGroup.nameKey(groupName)).orElse(null);
      if (group != null) {
        allowedUserGroups.add(group.getGroupUUID());
      }
    }
  }

  @Override
  public Response<Output> apply(RevisionResource rsrc, QtPrecheckMessage in)
      throws RestApiException, RepositoryNotFoundException, IOException, PermissionBackendException,
          UpdateException, ConfigInvalidException {
    logger.atInfo().log("precheck request for %s", rsrc.getChange().toString());

    if (!isPreCheckAllowedForUser(rsrc)) {
      throw new AuthException(
          String.format(
              "Precheck request from user %s without permission, %s",
              rsrc.getUser().getUserName(), rsrc.getChange().toString()));
    }

    if (!isPreCheckAllowedForProject(rsrc)) {
      throw new AuthException(
          String.format(
              "Precheck request for project not allowed, %s", rsrc.getChange().toString()));
    }

    Change change = rsrc.getChange();
    Output output;
    output = new Output(change);
    this.qtUtil.postChangePreCheckEvent(change, rsrc.getPatchSet(), in);

    // Generate user friendly message
    StringBuilder msg = new StringBuilder();
    msg.append("type: " + in.type);
    msg.append(", buildonly: " + in.onlybuild);
    msg.append(", cherrypick: " + in.cherrypick);
    if (in.type.equals("custom")) {
      msg.append(", input: " + in.platforms);
    }

    QtChangeUpdateOp op =
        qtUpdateFactory.create(
            null,
            null,
            String.format("Precheck requested with params %s", msg.toString()),
            null,
            QtUtil.TAG_CI,
            null);
    try (RefUpdateContext ctx = RefUpdateContext.open(CHANGE_MODIFICATION)) {
      try (BatchUpdate u =
          updateFactory.create(change.getProject(), rsrc.getUser(), TimeUtil.now())) {
        u.addOp(change.getId(), op).execute();
      }
      return Response.ok(output);
    }
  }

  @Override
  public UiAction.Description getDescription(RevisionResource resource) {

    Change change = resource.getChange();
    if (!change.getStatus().isOpen()
        || !resource.isCurrent()
        || !isPreCheckAllowedForUser(resource)) {
      return null; // precheck not visible
    }

    boolean enabled = isPreCheckAllowedForProject(resource);

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

  private boolean isPreCheckAllowedForUser(RevisionResource resource) {

    if (resource.getUser().getEffectiveGroups().containsAnyOf(allowedUserGroups)) return true;

    boolean canReview;
    try {
      canReview =
          resource
              .permissions()
              .test(
                  new LabelPermission.WithValue(
                      ForUser.SELF, LABEL_CODE_REVIEW, LABEL_CODE_REVIEW_VALUE));
    } catch (PermissionBackendException e) {
      logger.atInfo().log("%s", e.getMessage());
      return false;
    }

    return canReview;
  }

  private boolean isPreCheckAllowedForProject(RevisionResource resource) {
    return !Arrays.asList(disabledProjects).contains(resource.getProject().get());
  }
}
