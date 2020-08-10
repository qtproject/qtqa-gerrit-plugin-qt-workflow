//
// Copyright (C) 2020 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.client.Change.Status;
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
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.submit.IntegrationException;
import com.google.gerrit.server.submit.MergeOp;
import com.google.gerrit.server.update.UpdateException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

@Singleton
public class QtStage
    implements RestModifyView<RevisionResource, SubmitInput>, UiAction<RevisionResource> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String DEFAULT_TOOLTIP = "Stage patch set ${patchSet} into ${branch}";

  public static class Output {
    transient Change change;

    private Output(Change c) {
      change = c;
    }
  }

  private final ReentrantLock stageLock = new ReentrantLock();
  private final GitRepositoryManager repoManager;
  private final PermissionBackend permissionBackend;
  private final ChangeData.Factory changeDataFactory;
  private final ProjectCache projectCache;
  private final GitReferenceUpdated referenceUpdated;
  private final QtCherryPickPatch qtCherryPickPatch;
  private final QtUtil qtUtil;
  private final Provider<InternalChangeQuery> queryProvider;

  private final AccountResolver accountResolver;
  private final String label;
  private final ParameterizedString titlePattern;

  private Change change;
  private Project.NameKey projectKey;
  private Branch.NameKey destBranchKey;
  private Branch.NameKey stagingBranchKey;

  @Inject
  QtStage(
      GitRepositoryManager repoManager,
      PermissionBackend permissionBackend,
      ChangeData.Factory changeDataFactory,
      AccountResolver accountResolver,
      @GerritServerConfig Config cfg,
      ProjectCache projectCache,
      GitReferenceUpdated referenceUpdated,
      QtCherryPickPatch qtCherryPickPatch,
      QtUtil qtUtil,
      Provider<InternalChangeQuery> queryProvider) {

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
    this.queryProvider = queryProvider;
  }

  @Override
  public Output apply(RevisionResource rsrc, SubmitInput input)
      throws RestApiException, RepositoryNotFoundException, IOException, PermissionBackendException,
          UpdateException, ConfigInvalidException {

    Output output;
    logger.atInfo().log("qtcodereview: stage request reveived for %s", rsrc.getChange().toString());

    stageLock.lock();  // block processing of parallel stage requests
    try {
      IdentifiedUser submitter = rsrc.getUser().asIdentifiedUser();
      change = rsrc.getChange();
      projectKey = rsrc.getProject();
      destBranchKey = change.getDest();
      stagingBranchKey = QtUtil.getStagingBranch(destBranchKey);

      rsrc.permissions().check(ChangePermission.QT_STAGE);
      projectCache.checkedGet(rsrc.getProject()).checkStatePermitsWrite();

      output = new Output(changeToStaging(rsrc, submitter, input));
    } finally {
      stageLock.unlock();
    }

    return output;
  }

  private Change changeToStaging(RevisionResource rsrc, IdentifiedUser submitter, SubmitInput input)
      throws RestApiException, IOException, UpdateException, ConfigInvalidException,
          PermissionBackendException {
    logger.atInfo().log("qtcodereview: changeToStaging starts for %s", change.toString());

    if (change.getStatus() != Change.Status.NEW) {
      logger.atSevere().log(
          "qtcodereview: stage: change %s status wrong: %s", change, change.getStatus());
      throw new ResourceConflictException("Change is " + change.getStatus());
    } else if (!ProjectUtil.branchExists(repoManager, change.getDest())) {
      logger.atSevere().log(
          "qtcodereview: stage: change %s destination branch \"%s\" not found",
          change, change.getDest().get());
      throw new ResourceConflictException(
          String.format("Destination branch \"%s\" not found.", change.getDest().get()));
    } else if (!rsrc.getPatchSet().getId().equals(change.currentPatchSetId())) {
      logger.atSevere().log(
          "qtcodereview: stage: change %s revision %s is not current revision",
          change, rsrc.getPatchSet().getRevision().get());
      throw new ResourceConflictException(
          String.format("Revision %s is not current.", rsrc.getPatchSet().getRevision().get()));
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
        if (result == null)
          throw new NoSuchRefException("Cannot create staging ref: " + stagingBranchKey.get());
      }
      destId = git.resolve(stagingBranchKey.get());
      if (destId == null)
        throw new NoSuchRefException("Invalid Revision: " + stagingBranchKey.get());

      sourceId = git.resolve(rsrc.getPatchSet().getRevision().get());
      if (sourceId == null)
        throw new NoSuchRefException("Invalid Revision: " + rsrc.getPatchSet().getRevision().get());

      checkParents(git, rsrc);

      changeData = changeDataFactory.create(change);
      MergeOp.checkSubmitRule(changeData, false);

      CodeReviewCommit commit =
          qtCherryPickPatch.cherryPickPatch(
              changeData,
              projectKey,
              sourceId,
              destId,
              false, // allowFastForward
              Change.Status.STAGED,
              "Staged for CI", // defaultMessage
              null, // inputMessage
              QtUtil.TAG_CI // tag
              );
      Result result = qtUtil.updateRef(git, stagingBranchKey.get(), commit.toObjectId(), false);
      referenceUpdated.fire(
          projectKey, stagingBranchKey.get(), destId, commit.toObjectId(), submitter.state());

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
        qtUtil.postChangeStagedEvent(change);
        logger.atInfo().log(
            "qtcodereview: changeToStaging %s added to %s", change, stagingBranchKey);
        return change; // this doesn't return data to client, if needed use ChangeJson to convert it
      default:
        throw new ResourceConflictException("Change is unexpectedly " + change.getStatus());
    }
  }

  private void checkParents(RevisionResource resource) throws ResourceConflictException {
    try (final Repository repository = repoManager.openRepository(resource.getProject())) {
      checkParents(repository, resource);
    } catch (IOException e) {
      throw new ResourceConflictException("Can not read repository.", e);
    }
  }

  private void checkParents(Repository repository, RevisionResource resource) throws ResourceConflictException {
    try (final RevWalk rw = new RevWalk(repository)) {
      final PatchSet ps = resource.getPatchSet();
      final RevCommit rc = rw.parseCommit(ObjectId.fromString(ps.getRevision().get()));
      if (rc.getParentCount() < 2) {
          return;
      }
      for (final RevCommit parent : rc.getParents()) {
        final List<ChangeData> changes =
                queryProvider.get().enforceVisibility(true).byProjectCommit(resource.getProject(), parent);
        for (ChangeData cd : changes) {
          final Change change = cd.change();
          if (change.getStatus() != Status.MERGED) {
            throw new ResourceConflictException(String.format("Can not stage: Parent \"%s\" of a merged commit is not merged.", parent.name()));
          }
        }
      }
    } catch (IOException e) {
      throw new ResourceConflictException("Can not read repository.", e);
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
        checkParents(resource);
    } catch (ResourceConflictException e) {
        logger.atWarning().log("Parent(s) check failed. %s", e.getMessage());
        return null;
    }
    try {
      if (!projectCache.checkedGet(resource.getProject()).statePermitsWrite()) {
        return null; // stage not visible
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error checking if change is submittable");
      throw new StorageException("Could not determine problems for the change", e);
    }

    ChangeData cd = changeDataFactory.create(resource.getNotes());
    try {
      MergeOp.checkSubmitRule(cd, false);
    } catch (ResourceConflictException e) {
      return null; // stage not visible
    }
    Boolean enabled;
    // try {
    enabled = cd.isMergeable();
    // } catch (OrmException e) {
    //     throw new OrmRuntimeException("Could not determine mergeability", e);
    // }

    RevId revId = resource.getPatchSet().getRevision();
    Map<String, String> params =
        ImmutableMap.of(
            "patchSet", String.valueOf(resource.getPatchSet().getPatchSetId()),
            "branch", change.getDest().getShortName(),
            "commit", ObjectId.fromString(revId.get()).abbreviate(7).name());
    return new UiAction.Description()
        .setLabel(label)
        .setTitle(Strings.emptyToNull(titlePattern.replace(params)))
        .setVisible(true)
        .setEnabled(Boolean.TRUE.equals(enabled));
  }
}
