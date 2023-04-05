//
// Copyright (C) 2021-23 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.extensions.events.ChangeMerged;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.kohsuke.args4j.Option;

/**
 * A command to report pass or fail status for builds. When a build receives pass status, the branch
 * is updated with build ref and all open changes in the build are marked as merged. When a build
 * receives fail status, all change in the build are marked as new and they need to be staged again.
 *
 * <p>For example, how to approve a build $ ssh -p 29418 localhost gerrit-plugin-qt-workflow
 * staging-approve -p project -b master -i 123 -r=pass
 */
@CommandMetaData(
    name = "staging-approve",
    description =
        "Report pass or fail status for builds. If passed changed are merged into target branch.")
class QtCommandBuildApprove extends SshCommand {

  @Inject private PermissionBackend permissionBackend;

  @Inject private GitRepositoryManager gitManager;

  @Inject private BatchUpdate.Factory updateFactory;

  @Inject private PatchSetInserter.Factory patchSetInserterFactory;

  @Inject private GitReferenceUpdated referenceUpdated;

  @Inject private ChangeMerged changeMerged;

  @Inject private QtUtil qtUtil;

  @Inject private QtEmailSender qtEmailSender;

  @Inject private QtChangeUpdateOp.Factory qtUpdateFactory;

  private final ReentrantLock buildApproveLock = new ReentrantLock();

  @Option(
      name = "--project",
      aliases = {"-p"},
      required = true,
      usage = "project name")
  private String project;

  @Option(
      name = "--build-id",
      aliases = {"-i"},
      required = true,
      usage = "build branch containing changes, e.g. refs/builds/123 or 123")
  private String buildBranch;

  @Option(
      name = "--result",
      aliases = {"-r"},
      required = true,
      usage = "pass or fail")
  private String result;

  @Option(
      name = "--message",
      aliases = {"-m"},
      metaVar = "-|MESSAGE",
      usage = "message added to all changes")
  private String message;

  @Option(
      name = "--branch",
      aliases = {"-b"},
      metaVar = "BRANCH",
      required = true,
      usage = "destination branch, e.g. refs/heads/master or just master")
  private String destBranch;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Repository git;

  /** Parameter value for pass result. */
  private static final String PASS = "pass";
  /** Parameter value for fail result. */
  private static final String FAIL = "fail";
  /** Parameter value for stdin message. */
  private static final String STDIN_MESSAGE = "-";

  private Project.NameKey projectKey;
  private BranchNameKey buildBranchKey;
  private BranchNameKey destBranchKey;
  private BranchNameKey stagingBranchKey;
  private BranchNameKey destBranchShortKey;

  private List<Entry<ChangeData, RevCommit>> affectedChanges = null;

  @Override
  protected void run() throws UnloggedFailure {
    buildApproveLock.lock(); // block processing of parallel requests
    try {
      runBuildApprove();
    } finally {
      buildApproveLock.unlock();
    }
  }

  private void runBuildApprove() throws UnloggedFailure {
    logger.atInfo().log(
        "staging-approve -p %s -i %s -r %s -m %s -b %s",
        project, buildBranch, result, message, destBranch);

    readMessageParameter();

    projectKey = QtUtil.getProjectKey(project);
    buildBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_BUILDS, buildBranch);
    destBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_HEADS, destBranch);
    stagingBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_STAGING, destBranch);
    destBranchShortKey = QtUtil.getNameKeyShort(project, QtUtil.R_HEADS, destBranch);

    try {
      git = gitManager.openRepository(projectKey);

      // Check required permissions
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(destBranchKey.branch())
          .check(RefPermission.UPDATE);
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(stagingBranchKey.branch())
          .check(RefPermission.UPDATE);
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(buildBranchKey.branch())
          .check(RefPermission.READ);

      if (git.resolve(destBranchKey.branch()) == null) throw die("branch not found");
      if (git.resolve(buildBranchKey.branch()) == null) throw die("build not found");

      if (result.toLowerCase().equals(PASS)) {
        approveBuildChanges();
      } else if (result.toLowerCase().equals(FAIL)) {
        rejectBuildChanges();
      } else {
        throw die("result argument accepts only value pass or fail.");
      }

    } catch (AuthException e) {
      throw die("not authorized");
    } catch (PermissionBackendException e) {
      throw die("git permission error");
    } catch (RepositoryNotFoundException e) {
      throw die("project not found");
    } catch (IOException e) {
      throw die(e.getMessage());
    } catch (QtUtil.BranchNotFoundException e) {
      throw die("invalid branch " + e.getMessage());
    } catch (UpdateException | RestApiException | ConfigInvalidException e) {
      logger.atSevere().log("staging-napprove failed to update change status %s", e);
      throw die("Failed to update change status");
    } catch (QtUtil.MergeConflictException e) {
      String msg =
          String.format(
              "Merge build '%s' to branch '%s' failed", buildBranch, destBranchKey.shortName());
      logger.atSevere().log("%s", msg);
      throw die(
          String.format(
              "Merge conflict! build branch '%s' into '%s' failed", buildBranch, destBranch));
    } finally {
      if (git != null) git.close();
    }
  }

  private void approveBuildChanges()
      throws QtUtil.MergeConflictException, IOException, UpdateException, UnloggedFailure,
          RestApiException, ConfigInvalidException, QtUtil.BranchNotFoundException {
    if (message == null)
      message = String.format("Change merged into branch '%s'", destBranchKey.shortName());

    ObjectId oldId = git.resolve(destBranchKey.branch());

    try {
      affectedChanges =
          qtUtil.mergeIntegrationToBranch(
              user.asIdentifiedUser(),
              git,
              projectKey,
              buildBranchKey,
              destBranchKey,
              "Merge integration " + buildBranch);
    } catch (NoSuchRefException e) {
      message = "Gerrit plugin internal error. Please contact Gerrit Admin.";
      logger.atInfo().log("%s", e.getMessage());
      rejectBuildChanges();
      return;
    } catch (QtUtil.MergeConflictException e) {
      message =
          "Unable to merge this integration because another integration parallel to this one "
              + "successfully merged first and created a conflict in one of the tested changes.\n"
              + "Please review, resolve conflicts if necessary, and restage.";
      logger.atInfo().log("%s", e.getMessage());
      rejectBuildChanges();
      return;
    }

    updateChanges(
        affectedChanges,
        Change.Status.MERGED,
        Change.Status.INTEGRATING,
        message,
        ChangeMessagesUtil.TAG_MERGED,
        true);

    logger.atInfo().log(
        "build '%s' merged into branch '%s'", buildBranch, destBranchKey.shortName());

    // need to rebuild the staging ref to include recently merged changes
    qtUtil.rebuildStagingBranch(
        git, user.asIdentifiedUser(), projectKey, stagingBranchKey, destBranchShortKey);

    ObjectId newId = git.resolve(destBranchKey.branch());
    // send ref updated event only if there are changes to build
    if (!newId.equals(oldId)) {
      referenceUpdated.fire(
          projectKey, destBranchKey.branch(), oldId, newId, user.asIdentifiedUser().state());
    }

    // return merged sha1 to the caller
    stdout.println(newId.name());
  }

  private void rejectBuildChanges()
      throws QtUtil.MergeConflictException, UpdateException, RestApiException, IOException,
          ConfigInvalidException, QtUtil.BranchNotFoundException, UnloggedFailure {
    if (message == null)
      message = String.format("Change rejected for branch '%s'", destBranchKey.shortName());

    affectedChanges = qtUtil.listChangesNotMerged(git, buildBranchKey, destBranchKey);

    // Notify user that build did not have any open changes. The build has already been approved.
    if (affectedChanges.isEmpty()) {
      logger.atInfo().log(
          "build '%s' already in project '%s' branch '%s'",
          buildBranch, projectKey, destBranchKey.shortName());
      throw die("No open changes in the build branch");
    }

    updateChanges(
        affectedChanges,
        Change.Status.NEW,
        Change.Status.INTEGRATING,
        message,
        ChangeMessagesUtil.TAG_REVERT,
        false);

    logger.atInfo().log(
        "build '%s' rejected for branch '%s'", buildBranch, destBranchKey.shortName());
  }

  private void updateChanges(
      List<Entry<ChangeData, RevCommit>> list,
      Change.Status newStatus,
      Change.Status oldStatus,
      String changeMessage,
      String tag,
      Boolean passed)
      throws UpdateException, RestApiException, IOException, ConfigInvalidException {

    List<Entry<ChangeData, RevCommit>> emailingList =
        new ArrayList<Map.Entry<ChangeData, RevCommit>>();

    // do the db update
    QtChangeUpdateOp op =
        qtUpdateFactory.create(newStatus, oldStatus, changeMessage, null, tag, null);
    try (BatchUpdate u = updateFactory.create(projectKey, user, TimeUtil.now())) {
      for (Entry<ChangeData, RevCommit> item : list) {
        ChangeData cd = item.getKey();
        Change change = cd.change();
        if (change.getStatus() == oldStatus) {
          if (newStatus == Change.Status.MERGED) {
            ObjectId obj = git.resolve(cd.currentPatchSet().commitId().name());
            CodeReviewCommit currCommit = new CodeReviewCommit(obj);
            currCommit.setPatchsetId(cd.currentPatchSet().id());
            CodeReviewCommit newCommit = new CodeReviewCommit(item.getValue());
            Change.Id changeId = insertPatchSet(u, git, cd.notes(), newCommit);
            if (!changeId.equals(cd.getId())) {
              logger.atWarning().log(
                  "wrong changeId for new patchSet %s != %s", changeId, cd.getId());
            }
            u.addOp(
                changeId,
                qtUpdateFactory.create(newStatus, oldStatus, changeMessage, null, tag, currCommit));
          } else {
            u.addOp(change.getId(), op);
          }
          emailingList.add(item);
        }
      }
      u.execute();
    }

    // do rest
    for (Entry<ChangeData, RevCommit> item : emailingList) {
      ChangeData cd = item.getKey();
      Change change = cd.change();
      if (passed) {
        qtUtil.postChangeIntegrationPassEvent(change);
        sendMergeEvent(cd);
        qtEmailSender.sendMergedEmail(projectKey, change, user.getAccountId());
        logger.atInfo().log(
            "     change %s merged into '%s'", change.getId(), destBranchKey.shortName());
      } else {
        qtUtil.postChangeIntegrationFailEvent(change);
        qtEmailSender.sendBuildFailedEmail(projectKey, change, user.getAccountId(), message);
        logger.atInfo().log(
            "     change %s rejected for '%s'", change.getId(), destBranchKey.shortName());
      }
    }
  }

  private Change.Id insertPatchSet(
      BatchUpdate bu, Repository git, ChangeNotes destNotes, CodeReviewCommit cherryPickCommit)
      throws IOException, BadRequestException, ConfigInvalidException {
    Change destChange = destNotes.getChange();
    PatchSet.Id psId = ChangeUtil.nextPatchSetId(git, destChange.currentPatchSetId());
    PatchSetInserter inserter = patchSetInserterFactory.create(destNotes, psId, cherryPickCommit);
    inserter.setSendEmail(false).setAllowClosed(true);
    // .setCopyApprovals(true) doesn't work, so copying done in QtChangeUpdateOp
    bu.addOp(destChange.getId(), inserter);
    return destChange.getId();
  }

  private void sendMergeEvent(ChangeData changeData) {
    PatchSet ps = changeData.currentPatchSet();
    changeMerged.fire(changeData, ps, user.asIdentifiedUser().state(), ps.commitId().name(), TimeUtil.now());
  }

  private void readMessageParameter() throws UnloggedFailure {
    if (message == null) return;

    try {
      // User will submit message through stdin.
      if (message.equals(STDIN_MESSAGE)) {
        // Clear stdin indicator.
        message = "";

        // Read message from stdin.
        BufferedReader stdin = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = stdin.readLine()) != null) {
          message += line + "\n";
        }
      }
    } catch (IOException e) {
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    }
  }
}
