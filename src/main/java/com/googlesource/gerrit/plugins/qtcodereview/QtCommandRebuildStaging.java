//
// Copyright (C) 2019-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.kohsuke.args4j.Option;

@CommandMetaData(name = "staging-rebuild", description = "Rebuild a staging branch.")
class QtCommandRebuildStaging extends SshCommand {

  @Inject private PermissionBackend permissionBackend;

  @Inject private GitRepositoryManager gitManager;

  @Inject private BatchUpdate.Factory updateFactory;

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
      usage = "branch name, e.g. refs/heads/master or just master")
  private String branch;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Repository git;

  @Override
  protected void run() throws UnloggedFailure {
    logger.atInfo().log("staging-rebuild -p %s -b %s", project, branch);

    BranchNameKey stagingBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_STAGING, branch);
    BranchNameKey destBranchShortKey = QtUtil.getNameKeyShort(project, QtUtil.R_HEADS, branch);

    try {
      Project.NameKey projectKey = Project.nameKey(project);
      git = gitManager.openRepository(projectKey);

      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(destBranchShortKey.branch())
          .check(RefPermission.UPDATE);

      if (git.resolve(stagingBranchKey.branch()) == null) throw die("branch staging ref not found");

      qtUtil.rebuildStagingBranch(
          git, user.asIdentifiedUser(), projectKey, stagingBranchKey, destBranchShortKey);

      logger.atInfo().log("staging-rebuild done for %s", stagingBranchKey.shortName());
    } catch (AuthException e) {
      logger.atSevere().log("staging-rebuild Authentication failed to access repository: %s", e);
      throw die("not authorized");
    } catch (PermissionBackendException e) {
      logger.atSevere().log("staging-rebuild permission error %s", e);
    } catch (RepositoryNotFoundException e) {
      logger.atSevere().log("staging-rebuild repository not found: %s", e);
      throw die("project not found");
    } catch (IOException e) {
      logger.atSevere().log("staging-rebuild IOException %s", e);
      throw die(e.getMessage());
    } catch (QtUtil.MergeConflictException e) {
      logger.atSevere().log("staging-rebuild error %s", e);
      throw die("staging rebuild failed, merge conflict");
    } finally {
      if (git != null) {
        git.close();
      }
    }
  }
}
