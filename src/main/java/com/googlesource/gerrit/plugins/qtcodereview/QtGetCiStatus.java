//
// Copyright (C) 2023-24 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.text.StringEscapeUtils;

@Singleton
public class QtGetCiStatus implements RestReadView<AccountResource> {

  @Inject private QtCiStatusStorage statusStorage;

  private static final String ciStatusUnknownColor = "var(--disabled-foreground)";

  public static class StatusInfo {
    public String message;
    public String statusColor;
  }

  @Override
  public Response<StatusInfo> apply(AccountResource rsrc) {

    StatusInfo statusInfo = new StatusInfo();
    statusInfo.message = "";
    statusInfo.statusColor = ciStatusUnknownColor;
    if (!rsrc.getUser().isIdentifiedUser()) return Response.ok(statusInfo);

    QtCiStatusStorage.CiResourceItem items[] = statusStorage.getData();
    statusInfo.message = generateHtml(items);
    statusInfo.statusColor = maxLoadColor(items);

    return Response.ok(statusInfo);
  }

  private String generateHtml(QtCiStatusStorage.CiResourceItem items[]) {

    StringBuilder sb = new StringBuilder();
    sb.append("<style>");
    sb.append(".cistatuslist { border-top: 1px solid var(--border-color);");
    sb.append(" padding: var(--spacing-m) var(--spacing-l); }");
    sb.append(".cistatusitem { font-size: var(--font-size-small);");
    sb.append(" margin-left: var(--spacing-l);}");
    sb.append("</style>");
    sb.append("<div class=\"dropdown-content\">");
    sb.append("<span style=\"padding: var(--spacing-m) var(--spacing-l); display: block;");
    sb.append(" background-color: var(--selection-background-color);\">");

    if (items != null) {
      sb.append("Workload</span>");
      for (QtCiStatusStorage.CiResourceItem r : items) {
        sb.append(generateSectionHtml(r.name, r.running, r.queue, r.load));
      }
    } else {
      sb.append("Not updated</span>");
    }
    sb.append("</div>");

    return sb.toString();
  }

  private String generateSectionHtml(String name, Integer running, Integer queue, Integer load) {
    StringBuilder sb = new StringBuilder();
    sb.append("<div class=\"cistatuslist\">").append(StringEscapeUtils.escapeHtml4(name));
    sb.append("<div class=\"cistatusitem\">");
    sb.append("<span><b>").append(running).append("</b> running</span><br>");
    sb.append("<span style=\"color: ").append(loadColor(load)).append(";\">");
    sb.append("<b>").append(queue).append("</b>");
    sb.append("</span>");
    sb.append("<span> in queue</span>");
    sb.append("</div>");
    sb.append("</div>");
    return sb.toString();
  }

  private String maxLoadColor(QtCiStatusStorage.CiResourceItem items[]) {
    Integer load = -1;
    if (items != null) {
      for (QtCiStatusStorage.CiResourceItem r : items) {
        if (r.load > load) load = r.load;
      }
    }
    return loadColor(load);
  }

  private String loadColor(Integer load) {
    var color = ciStatusUnknownColor;
    if (load == 0) color = "var(--success-foreground)";
    else if (load == 1) color = "var(--warning-foreground)";
    else if (load > 1) color = "var(--error-foreground)";
    return color;
  }
}
