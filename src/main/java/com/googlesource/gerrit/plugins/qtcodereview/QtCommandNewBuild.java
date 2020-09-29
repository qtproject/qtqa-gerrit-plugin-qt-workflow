//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.kohsuke.args4j.Option;

@CommandMetaData(
    name = "staging-new-build",
    description =
        "Create unique build branch of the current staging branch and change the gerrit status of the changes to INTEGRATING.")
class QtCommandNewBuild extends SshCommand {

  @Inject private PermissionBackend permissionBackend;

  @Inject private GitRepositoryManager gitManager;

  @Inject private BatchUpdate.Factory updateFactory;

  @Inject private QtUtil qtUtil;

  @Inject private QtChangeUpdateOp.Factory qtUpdateFactory;

  @Option(
      name = "--project",
      aliases = {"-p"},
      required = true,
      usage = "project name")
  private String project;

  @Option(
      name = "--staging-branch",
      aliases = {"-s"},
      required = true,
      usage = "branch name, e.g. refs/staging/master or just master")
  private String stagingBranch;

  @Option(
      name = "--build-id",
      aliases = {"-i"},
      required = true,
      usage = "build id, e.g. refs/builds/my_build or just my_build")
  private String build;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Repository git;

  @Override
  protected void run() throws UnloggedFailure {

    logger.atInfo().log(
        "qtcodereview: staging-new-build -p %s -s %s -i %s", project, stagingBranch, build);

    try {
      Project.NameKey projectKey = Project.nameKey(project);
      git = gitManager.openRepository(projectKey);

      BranchNameKey buildBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_BUILDS, build);
      BranchNameKey stagingBranchKey =
          QtUtil.getNameKeyLong(project, QtUtil.R_STAGING, stagingBranch);
      BranchNameKey destBranchShortKey =
          QtUtil.getNameKeyShort(project, QtUtil.R_STAGING, stagingBranch);
      BranchNameKey destinationKey = QtUtil.getNameKeyLong(project, QtUtil.R_HEADS, stagingBranch);

      // Check required permissions
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(destinationKey.branch())
          .check(RefPermission.UPDATE);
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(buildBranchKey.branch())
          .check(RefPermission.CREATE);

      if (QtUtil.branchExists(git, buildBranchKey) == true) {
        logger.atSevere().log(
            "qtcodereview: staging-new-build Target build %s already exists", buildBranchKey);
        throw die("Target build already exists!");
      }

      if (QtUtil.branchExists(git, stagingBranchKey) == false) {
        logger.atSevere().log(
            "qtcodereview: staging-new-build staging ref %s not found", stagingBranchKey);
        throw die("Staging ref not found!");
      }

      // Create build reference.
      Result result =
          qtUtil.createBuildRef(
              git, user.asIdentifiedUser(), projectKey, stagingBranchKey, buildBranchKey);
      String message = String.format("Added to build %s for %s", build, destinationKey);

      if (result != Result.NEW && result != Result.FAST_FORWARD) {
        logger.atSevere().log(
            "qtcodereview: staging-new-build failed to create new build ref %s result %s",
            buildBranchKey, result);
        throw new UnloggedFailure(1, "fatal: failed to create new build ref: " + result);
      } else {
        // list the changes in staging branch but missing from the destination branch
        List<Entry<ChangeData, RevCommit>> openChanges =
            qtUtil.listChangesNotMerged(git, buildBranchKey, destBranchShortKey);

        // Make sure that there are changes in the staging branch.
        if (openChanges.isEmpty()) {
          logger.atSevere().log(
              "qtcodereview: staging-new-build No changes in staging branch %s.", stagingBranchKey);
          throw die("No changes in staging branch. Not creating a build reference");
        }

        QtChangeUpdateOp op =
            qtUpdateFactory.create(
                Change.Status.INTEGRATING,
                Change.Status.STAGED,
                message,
                null,
                QtUtil.TAG_CI,
                null);
        try (BatchUpdate u = updateFactory.create(projectKey, user, TimeUtil.nowTs())) {
          for (Entry<ChangeData, RevCommit> item : openChanges) {
            Change change = item.getKey().change();
            if (change.getStatus() == Change.Status.STAGED) {
              logger.atInfo().log(
                  "qtcodereview: staging-new-build     inserted change %s (%s) into build %s for %s",
                  change, item.getValue().toString(), build, destinationKey);
              u.addOp(change.getId(), op);
            } else {
              logger.atInfo().log(
                  "qtcodereview: staging-new-build     change %s (%s) is included in build %s for %s",
                  change, item.getValue().toString(), build, destinationKey);
            }
          }
          u.execute();
        }
      }

      // reset staging ref back to branch head
      result = QtUtil.createStagingBranch(git, destBranchShortKey);

      logger.atInfo().log(
          "qtcodereview: staging-new-build build %s for %s created", build, destBranchShortKey);

    } catch (AuthException e) {
      logger.atSevere().log(
          "qtcodereview: staging-new-build Authentication failed to access repository: %s", e);
      throw die("Authentication failed to access repository");
    } catch (PermissionBackendException e) {
      logger.atSevere().log(
          "qtcodereview: staging-new-build Not enough permissions to access repository %s", e);
      throw die("Not enough permissions to access repository");
    } catch (RepositoryNotFoundException e) {
      throw die("project not found");
    } catch (IOException e) {
      logger.atSevere().log("qtcodereview: staging-new-build Failed to access repository %s", e);
      throw die("Failed to access repository");
    } catch (QtUtil.BranchNotFoundException e) {
      logger.atSevere().log(
          "qtcodereview: staging-new-build Failed to access build or staging ref %s", e);
      throw die("Failed to access build or staging ref");
    } catch (NoSuchRefException e) {
      logger.atSevere().log("qtcodereview: staging-new-build Invalid branch name %s", e);
      throw die("Invalid branch name");
    } catch (UpdateException | RestApiException e) {
      logger.atSevere().log("qtcodereview: staging-new-build failed to update change status %s", e);
      throw die("Failed to update change status");
    } finally {
      if (git != null) {
        git.close();
      }
    }
  }
}
