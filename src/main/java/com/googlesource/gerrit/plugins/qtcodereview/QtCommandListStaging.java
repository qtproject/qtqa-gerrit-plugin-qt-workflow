//
// Copyright (C) 2019-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map.Entry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.kohsuke.args4j.Option;

@CommandMetaData(
    name = "staging-ls",
    description =
        "List all the changes that have been applied to the staging or build ref that are not in"
            + " the destination branch yet.")
class QtCommandListStaging extends SshCommand {

  @Inject private PermissionBackend permissionBackend;

  @Inject private GitRepositoryManager gitManager;

  @Inject private QtUtil qtUtil;

  @Option(
      name = "--project",
      aliases = {"-p"},
      required = true,
      usage = "project name")
  private String project;

  @Option(
      name = "--branch",
      aliases = {"-b"},
      required = true,
      usage = "any ref, e.g. refs/staging/master or refs/builds/my_build")
  private String branch;

  @Option(
      name = "--destination",
      aliases = {"-d"},
      required = true,
      usage = "destination branch filter, e.g. refs/heads/master or just master")
  private String destination;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Repository git;

  @Override
  protected void run() throws UnloggedFailure {
    logger.atInfo().log("staging-ls -p %s -b %s", project, branch);

    final PrintWriter stdout = toPrintWriter(out);

    Project.NameKey projectKey = Project.nameKey(project);
    BranchNameKey aBranchKey = BranchNameKey.create(projectKey, branch);
    BranchNameKey destBranchShortKey = QtUtil.getNameKeyShort(project, QtUtil.R_HEADS, destination);

    try {
      git = gitManager.openRepository(projectKey);

      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(aBranchKey.branch())
          .check(RefPermission.READ);
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(destBranchShortKey.branch())
          .check(RefPermission.READ);

      if (git.resolve(aBranchKey.branch()) == null) {
        throw die("branch ref not found");
      }

      final List<Entry<ChangeData, RevCommit>> open =
          qtUtil.listChangesNotMerged(git, aBranchKey, destBranchShortKey);

      for (Entry<ChangeData, RevCommit> item : open) {
        final Change change = item.getKey().change();
        final Change.Status status = change.getStatus();

        if (status == Change.Status.STAGED || status == Change.Status.INTEGRATING) {
          final RevCommit commit = item.getValue();
          stdout.println(
              commit.name() + " " + change.currentPatchSetId() + " " + change.getSubject());
        }
      }

      logger.atInfo().log("staging-ls done");
    } catch (AuthException e) {
      logger.atSevere().log("staging-ls Authentication failed to access repository: %s", e);
      throw die("not authorized");
    } catch (PermissionBackendException e) {
      logger.atSevere().log("staging-ls permission error %s", e);
    } catch (RepositoryNotFoundException e) {
      logger.atSevere().log("staging-ls repository not found: %s", e);
      throw die("project not found");
    } catch (QtUtil.BranchNotFoundException e) {
      throw die("invalid branch " + e.getMessage());
    } catch (IOException e) {
      logger.atSevere().log("staging-ls IOException %s", e);
      throw die(e.getMessage());
    } finally {
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }
}
