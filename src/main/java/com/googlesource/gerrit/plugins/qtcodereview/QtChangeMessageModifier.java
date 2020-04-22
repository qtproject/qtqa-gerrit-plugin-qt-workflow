//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.inject.Singleton;
import org.eclipse.jgit.revwalk.RevCommit;

@Singleton
public class QtChangeMessageModifier implements ChangeMessageModifier {

  // Remove extra commit message footers
  @Override
  public String onSubmit(
      String commitMessage, RevCommit original, RevCommit mergeTip, BranchNameKey destination) {
    StringBuilder stringBuilder = new StringBuilder("");
    String[] lines = commitMessage.split("\n");

    for (String line : lines) {
      if (!line.startsWith("Reviewed-on: ")
          && !line.startsWith("Tested-by: ")
          && !line.startsWith("Sanity-Review: ")
          && !line.startsWith("ChangeLog: ")) {
        stringBuilder.append(line).append("\n");
      }
    }
    return stringBuilder.toString();
  }
}
