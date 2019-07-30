//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.ProjectUtil;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.submit.IntegrationException;
import com.google.gerrit.server.submit.MergeOp;
import com.google.gerrit.server.update.UpdateException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.OrmRuntimeException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Map;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RefUpdate.Result;

@Singleton
public class QtStage implements RestModifyView<RevisionResource, SubmitInput>,
                                          UiAction<RevisionResource> {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private static final String DEFAULT_TOOLTIP = "Stage patch set ${patchSet} into ${branch}";

    public static class Output {
        transient Change change;
        private Output(Change c) {
            change = c;
        }
    }

    private final Provider<ReviewDb> dbProvider;
    private final GitRepositoryManager repoManager;
    private final PermissionBackend permissionBackend;
    private final ChangeData.Factory changeDataFactory;
    private final ProjectCache projectCache;
    private final GitReferenceUpdated referenceUpdated;
    private final QtCherryPickPatch qtCherryPickPatch;
    private final QtUtil qtUtil;

    private final AccountResolver accountResolver;
    private final String label;
    private final ParameterizedString titlePattern;

    private Change change;
    private Project.NameKey projectKey;
    private Branch.NameKey destBranchKey;
    private Branch.NameKey stagingBranchKey;

    @Inject
    QtStage(Provider<ReviewDb> dbProvider,
            GitRepositoryManager repoManager,
            PermissionBackend permissionBackend,
            ChangeData.Factory changeDataFactory,
            AccountResolver accountResolver,
            @GerritServerConfig Config cfg,
            ProjectCache projectCache,
            GitReferenceUpdated referenceUpdated,
            QtCherryPickPatch qtCherryPickPatch,
            QtUtil qtUtil) {

        this.dbProvider = dbProvider;
        this.repoManager = repoManager;
        this.permissionBackend = permissionBackend;
        this.changeDataFactory = changeDataFactory;
        this.accountResolver = accountResolver;
        this.label = "Stage";
        this.titlePattern =
            new ParameterizedString(
                MoreObjects.firstNonNull(
                    cfg.getString("change", null, "stageTooltip"), DEFAULT_TOOLTIP));
        this.projectCache = projectCache;
        this.referenceUpdated = referenceUpdated;
        this.qtCherryPickPatch = qtCherryPickPatch;
        this.qtUtil = qtUtil;
    }

    @Override
    public Output apply(RevisionResource rsrc, SubmitInput input)
      throws RestApiException, RepositoryNotFoundException, IOException, OrmException,
          PermissionBackendException, UpdateException, ConfigInvalidException {

        logger.atInfo().log("qtcodereview: submit %s to staging", rsrc.getChange().toString());

        IdentifiedUser submitter = rsrc.getUser().asIdentifiedUser();
        change = rsrc.getChange();
        projectKey = rsrc.getProject();
        destBranchKey = change.getDest();
        stagingBranchKey = QtUtil.getStagingBranch(destBranchKey);

        rsrc.permissions().check(ChangePermission.QT_STAGE);

        projectCache.checkedGet(rsrc.getProject()).checkStatePermitsWrite();

        return new Output(changeToStaging(rsrc, submitter, input));
    }

    private Change changeToStaging(RevisionResource rsrc, IdentifiedUser submitter, SubmitInput input)
        throws OrmException, RestApiException, IOException, UpdateException, ConfigInvalidException,
            PermissionBackendException {
        logger.atInfo().log("qtcodereview: changeToStaging starts");

        if (change.getStatus() != Change.Status.NEW) {
            logger.atSevere().log("qtcodereview: stage: change %s status wrong: %s",
                                  change, change.getStatus());
            throw new ResourceConflictException("Change is " + change.getStatus());
        } else if (!ProjectUtil.branchExists(repoManager, change.getDest())) {
            logger.atSevere().log("qtcodereview: stage: change %s destination branch \"%s\" not found",
                                  change, change.getDest().get());
            throw new ResourceConflictException(String.format("Destination branch \"%s\" not found.",
                                                              change.getDest().get()));
        } else if (!rsrc.getPatchSet().getId().equals(change.currentPatchSetId())) {
            logger.atSevere().log("qtcodereview: stage: change %s revision %s is not current revision",
                                  change, rsrc.getPatchSet().getRevision().get());
            throw new ResourceConflictException(String.format("Revision %s is not current.",
                                                              rsrc.getPatchSet().getRevision().get()));
        }

        Repository git = null;
        ObjectId destId = null;
        ObjectId sourceId = null;
        ChangeData changeData;

        try {
            git = repoManager.openRepository(projectKey);
            // Check if staging branch exists. Create the staging branch if it does not exist.
            if (!ProjectUtil.branchExists(repoManager, stagingBranchKey)) {
                Result result = QtUtil.createStagingBranch(git, destBranchKey);
                if (result == null) throw new NoSuchRefException("Cannot create staging ref: " + stagingBranchKey.get());
            }
            destId = git.resolve(stagingBranchKey.get());
            if (destId == null) throw new NoSuchRefException("Invalid Revision: " + stagingBranchKey.get());

            sourceId = git.resolve(rsrc.getPatchSet().getRevision().get());
            if (sourceId == null) throw new NoSuchRefException("Invalid Revision: " + rsrc.getPatchSet().getRevision().get());

            changeData = changeDataFactory.create(dbProvider.get(), change);
            MergeOp.checkSubmitRule(changeData, false);

            CodeReviewCommit commit = qtCherryPickPatch.cherryPickPatch(changeData,
                                                                        projectKey,
                                                                        sourceId,
                                                                        destId,
                                                                        false, // allowFastForward
                                                                        Change.Status.STAGED,
                                                                        "Staged for CI", // defaultMessage
                                                                        null, // inputMessage
                                                                        QtUtil.TAG_CI  // tag
                                                                        );
            Result result = qtUtil.updateRef(git, stagingBranchKey.get(), commit.toObjectId(), false);
            referenceUpdated.fire(projectKey, stagingBranchKey.get(), destId, commit.toObjectId(), submitter.state());

        } catch (IntegrationException e) {
            logger.atInfo().log("qtcodereview: stage merge error %s", e);
            throw new ResourceConflictException(e.getMessage());
        } catch (NoSuchRefException e) {
            logger.atSevere().log("qtcodereview: stage error %s", e);
            throw new ResourceConflictException(e.getMessage());
        } finally {
            if (git != null) {
                git.close();
            }
        }

        change = changeData.reloadChange();
        switch (change.getStatus()) {
          case STAGED:
              logger.atInfo().log("qtcodereview: changeToStaging %s added to %s", change, stagingBranchKey);
              return change; // this doesn't return data to client, if needed use ChangeJson to convert it
          default:
              throw new ResourceConflictException("Change is unexpectedly " + change.getStatus());
        }

    }

    @Override
    public UiAction.Description getDescription(RevisionResource resource) {
        Change change = resource.getChange();
        if (!change.getStatus().isOpen()
            || change.isWorkInProgress()
            || !resource.isCurrent()
            || !resource.permissions().testOrFalse(ChangePermission.QT_STAGE)) {
            return null; // submit not visible
        }
        try {
            if (!projectCache.checkedGet(resource.getProject()).statePermitsWrite()) {
                return null; // stage not visible
            }
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Error checking if change is submittable");
            throw new OrmRuntimeException("Could not determine problems for the change", e);
        }

        ReviewDb db = dbProvider.get();
        ChangeData cd = changeDataFactory.create(db, resource.getNotes());
        try {
            MergeOp.checkSubmitRule(cd, false);
        } catch (ResourceConflictException e) {
            return null; // stage not visible
        } catch (OrmException e) {
            logger.atSevere().withCause(e).log("Error checking if change is submittable");
            throw new OrmRuntimeException("Could not determine problems for the change", e);
        }

        Boolean enabled;
        try {
            enabled = cd.isMergeable();
        } catch (OrmException e) {
            throw new OrmRuntimeException("Could not determine mergeability", e);
        }

        RevId revId = resource.getPatchSet().getRevision();
        Map<String, String> params =
            ImmutableMap.of(
                "patchSet", String.valueOf(resource.getPatchSet().getPatchSetId()),
                "branch", change.getDest().getShortName(),
                "commit", ObjectId.fromString(revId.get()).abbreviate(7).name() );
        return new UiAction.Description()
            .setLabel( label)
            .setTitle(Strings.emptyToNull(titlePattern.replace(params)))
            .setVisible(true)
            .setEnabled(Boolean.TRUE.equals(enabled));
  }

}
