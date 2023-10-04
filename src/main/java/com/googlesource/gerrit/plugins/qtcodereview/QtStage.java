//
// Copyright (C) 2020-23 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Change.Status;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.ProjectUtil;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.IdentifiedUser;
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
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.submit.IntegrationConflictException;
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
  private BranchNameKey destBranchKey;
  private BranchNameKey stagingBranchKey;

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
  public Response<Output> apply(RevisionResource rsrc, SubmitInput input)
      throws RestApiException, RepositoryNotFoundException, IOException, PermissionBackendException,
          UpdateException, ConfigInvalidException {

    Output output;
    logger.atInfo().log("stage request reveived for %s", rsrc.getChange().toString());

    stageLock.lock(); // block processing of parallel stage requests
    try {
      IdentifiedUser submitter = rsrc.getUser().asIdentifiedUser();
      change = rsrc.getChange();
      projectKey = rsrc.getProject();
      destBranchKey = change.getDest();
      stagingBranchKey = QtUtil.getStagingBranch(destBranchKey);

      rsrc.permissions().check(ChangePermission.QT_STAGE);
      projectCache
          .get(rsrc.getProject())
          .orElseThrow(illegalState(rsrc.getProject()))
          .checkStatePermitsWrite();

      output = new Output(changeToStaging(rsrc, submitter, input));
    } finally {
      stageLock.unlock();
    }

    return Response.ok(output);
  }

  private Change changeToStaging(RevisionResource rsrc, IdentifiedUser submitter, SubmitInput input)
      throws RestApiException, IOException, UpdateException, ConfigInvalidException,
          PermissionBackendException {
    logger.atInfo().log("changeToStaging starts for %s", change.getId());

    if (change.getStatus() != Change.Status.NEW) {
      logger.atSevere().log(
          "stage: change %s status wrong: %s", change.getId(), change.getStatus());
      throw new ResourceConflictException("Change is " + change.getStatus());
    } else if (!QtUtil.branchExists(repoManager, change.getDest())) {
      logger.atSevere().log(
          "stage: change %s destination branch \"%s\" not found",
          change, change.getDest().branch());
      throw new ResourceConflictException(
          String.format("Destination branch \"%s\" not found.", change.getDest().branch()));
    } else if (!rsrc.getPatchSet().id().equals(change.currentPatchSetId())) {
      logger.atSevere().log(
          "stage: change %s revision %s is not current revision",
          change.getId(), rsrc.getPatchSet().commitId());
      throw new ResourceConflictException(
          String.format("Revision %s is not current.", rsrc.getPatchSet().commitId()));
    }

    Repository git = null;
    ObjectId destId = null;
    ObjectId sourceId = null;
    ChangeData changeData;

    try {
      git = repoManager.openRepository(projectKey);
      // Check if staging branch exists. Create the staging branch if it does not exist.
      if (!QtUtil.branchExists(repoManager, stagingBranchKey)) {
        Result result = QtUtil.createStagingBranch(git, destBranchKey);
        if (result == null)
          throw new NoSuchRefException("Cannot create staging ref: " + stagingBranchKey.branch());
      }
      destId = git.resolve(stagingBranchKey.branch());
      if (destId == null)
        throw new NoSuchRefException("Invalid Revision: " + stagingBranchKey.branch());

      sourceId = git.resolve(rsrc.getPatchSet().commitId().name());
      if (sourceId == null)
        throw new NoSuchRefException("Invalid Revision: " + rsrc.getPatchSet().commitId());

      checkParents(git, rsrc);

      changeData = changeDataFactory.create(change);
      MergeOp.checkSubmitRequirements(changeData);

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
      Result result = qtUtil.updateRef(git, stagingBranchKey.branch(), commit.toObjectId(), false);
      referenceUpdated.fire(
          projectKey, stagingBranchKey.branch(), destId, commit.toObjectId(), submitter.state());

    } catch (IntegrationConflictException e) {
      logger.atInfo().log("stage merge error %s", e);
      throw new ResourceConflictException(e.getMessage());
    } catch (NoSuchRefException e) {
      logger.atSevere().log("stage error %s", e);
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
            "changeToStaging %s,%s added to %s",
            change.getId(), change.getKey(), stagingBranchKey.branch());
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

  private void checkParents(Repository repository, RevisionResource resource)
      throws ResourceConflictException {
    try (final RevWalk rw = new RevWalk(repository)) {
      final PatchSet ps = resource.getPatchSet();
      final RevCommit rc = rw.parseCommit(ObjectId.fromString(ps.commitId().name()));
      if (rc.getParentCount() < 2) {
        return;
      }
      for (final RevCommit parent : rc.getParents()) {
        final List<ChangeData> changes =
            queryProvider
                .get()
                .enforceVisibility(true)
                .byProjectCommit(resource.getProject(), parent);
        for (ChangeData cd : changes) {
          final Change change = cd.change();
          if (change.getStatus() != Status.MERGED) {
            throw new ResourceConflictException(
                String.format(
                    "Can not stage: Parent \"%s\" of a merged commit is not merged.",
                    parent.name()));
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
      if (!projectCache
          .get(resource.getProject())
          .map(ProjectState::statePermitsWrite)
          .orElse(false)) {
        return null; // stage not visible
      }
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Error checking if change is submittable");
      throw new StorageException("Could not determine problems for the change", e);
    }

    ChangeData cd = changeDataFactory.create(resource.getNotes());
    try {
      MergeOp.checkSubmitRequirements(cd);
    } catch (ResourceConflictException e) {
      return null; // stage not visible
    }

    ObjectId revId = resource.getPatchSet().commitId();
    Map<String, String> params =
        ImmutableMap.of(
            "patchSet", String.valueOf(resource.getPatchSet().number()),
            "branch", change.getDest().shortName(),
            "commit", revId.abbreviate(7).name());
    return new UiAction.Description()
        .setLabel(label)
        .setTitle(Strings.emptyToNull(titlePattern.replace(params)))
        .setVisible(true)
        .setEnabled(true);
  }
}
