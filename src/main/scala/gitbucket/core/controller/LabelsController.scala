package gitbucket.core.controller

import gitbucket.core.issues.labels.html
import gitbucket.core.service.{RepositoryService, AccountService, IssuesService, LabelsService}
import gitbucket.core.util.{ReferrerAuthenticator, WritableUsersAuthenticator}
import gitbucket.core.util.Implicits._
import io.github.gitbucket.scalatra.forms._
import org.scalatra.i18n.Messages
import org.scalatra.Ok

class LabelsController extends LabelsControllerBase
  with LabelsService with IssuesService with RepositoryService with AccountService
with ReferrerAuthenticator with WritableUsersAuthenticator

trait LabelsControllerBase extends ControllerBase {
  self: LabelsService with IssuesService with RepositoryService
    with ReferrerAuthenticator with WritableUsersAuthenticator =>

  case class LabelForm(labelName: String, color: String)

  val labelForm = mapping(
    "labelName"  -> trim(label("Label name", text(required, labelName, uniqueLabelName, maxlength(100)))),
    "labelColor" -> trim(label("Color",      text(required, color)))
  )(LabelForm.apply)


  get("/:owner/:repository/issues/labels")(referrersOnly { repository =>
    html.list(
      getLabels(repository.owner, repository.name),
      countIssueGroupByLabels(repository.owner, repository.name, IssuesService.IssueSearchCondition(), Map.empty),
      repository,
      hasDeveloperRole(repository.owner, repository.name, context.loginAccount))
  })

  ajaxGet("/:owner/:repository/issues/labels/new")(writableUsersOnly { repository =>
    html.edit(None, repository)
  })

  ajaxPost("/:owner/:repository/issues/labels/new", labelForm)(writableUsersOnly { (form, repository) =>
    val labelId = createLabel(repository.owner, repository.name, form.labelName, form.color.substring(1))
    html.label(
      getLabel(repository.owner, repository.name, labelId).get,
      // TODO futility
      countIssueGroupByLabels(repository.owner, repository.name, IssuesService.IssueSearchCondition(), Map.empty),
      repository,
      hasDeveloperRole(repository.owner, repository.name, context.loginAccount))
  })

  ajaxGet("/:owner/:repository/issues/labels/:labelId/edit")(writableUsersOnly { repository =>
    getLabel(repository.owner, repository.name, params("labelId").toInt).map { label =>
      html.edit(Some(label), repository)
    } getOrElse NotFound()
  })

  ajaxPost("/:owner/:repository/issues/labels/:labelId/edit", labelForm)(writableUsersOnly { (form, repository) =>
    updateLabel(repository.owner, repository.name, params("labelId").toInt, form.labelName, form.color.substring(1))
    html.label(
      getLabel(repository.owner, repository.name, params("labelId").toInt).get,
      // TODO futility
      countIssueGroupByLabels(repository.owner, repository.name, IssuesService.IssueSearchCondition(), Map.empty),
      repository,
      hasDeveloperRole(repository.owner, repository.name, context.loginAccount))
  })

  ajaxPost("/:owner/:repository/issues/labels/:labelId/delete")(writableUsersOnly { repository =>
    deleteLabel(repository.owner, repository.name, params("labelId").toInt)
    Ok()
  })

  /**
   * Constraint for the identifier such as user name, repository name or page name.
   */
  private def labelName: Constraint = new Constraint(){
    override def validate(name: String, value: String, messages: Messages): Option[String] =
      if(value.contains(',')){
        Some(s"${name} contains invalid character.")
      } else if(value.startsWith("_") || value.startsWith("-")){
        Some(s"${name} starts with invalid character.")
      } else {
        None
      }
  }

  private def uniqueLabelName: Constraint = new Constraint(){
    override def validate(name: String, value: String, params: Map[String, String], messages: Messages): Option[String] = {
      val owner = params("owner")
      val repository = params("repository")
      params.get("labelId").map { labelId =>
        getLabel(owner, repository, value).filter(_.labelId != labelId.toInt).map(_ => "Name has already been taken.")
      }.getOrElse {
        getLabel(owner, repository, value).map(_ => "Name has already been taken.")
      }
    }
  }

}