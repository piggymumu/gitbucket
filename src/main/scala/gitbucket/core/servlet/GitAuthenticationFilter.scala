package gitbucket.core.servlet

import javax.servlet._
import javax.servlet.http._
import gitbucket.core.plugin.{GitRepositoryFilter, GitRepositoryRouting, PluginRegistry}
import gitbucket.core.service.SystemSettingsService.SystemSettings
import gitbucket.core.service.{RepositoryService, AccountService, SystemSettingsService}
import gitbucket.core.util.{Keys, Implicits, AuthUtil}
import gitbucket.core.model.Profile.profile.blockingApi._
import org.slf4j.LoggerFactory
import Implicits._

/**
 * Provides BASIC Authentication for [[GitRepositoryServlet]].
 */
class GitAuthenticationFilter extends Filter with RepositoryService with AccountService with SystemSettingsService {

  private val logger = LoggerFactory.getLogger(classOf[GitAuthenticationFilter])

  def init(config: FilterConfig) = {}

  def destroy(): Unit = {}

  def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain): Unit = {
    val request  = req.asInstanceOf[HttpServletRequest]
    val response = res.asInstanceOf[HttpServletResponse]

    val wrappedResponse = new HttpServletResponseWrapper(response){
      override def setCharacterEncoding(encoding: String) = {}
    }

    val isUpdating = request.getRequestURI.endsWith("/git-receive-pack") || "service=git-receive-pack".equals(request.getQueryString)
    val settings = loadSystemSettings()

    try {
      PluginRegistry().getRepositoryRouting(request.gitRepositoryPath).map { case GitRepositoryRouting(_, _, filter) =>
        // served by plug-ins
        pluginRepository(request, wrappedResponse, chain, settings, isUpdating, filter)

      }.getOrElse {
        // default repositories
        defaultRepository(request, wrappedResponse, chain, settings, isUpdating)
      }
    } catch {
      case ex: Exception => {
        logger.error("error", ex)
        AuthUtil.requireAuth(response)
      }
    }
  }

  private def pluginRepository(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain,
                               settings: SystemSettings, isUpdating: Boolean, filter: GitRepositoryFilter): Unit = {
    Database() withSession { implicit session =>
      val account = for {
        auth <- Option(request.getHeader("Authorization"))
        Array(username, password) = AuthUtil.decodeAuthHeader(auth).split(":", 2)
        account <- authenticate(settings, username, password)
      } yield {
        request.setAttribute(Keys.Request.UserName, account.userName)
        account
      }

      if (filter.filter(request.gitRepositoryPath, account.map(_.userName), settings, isUpdating)) {
        chain.doFilter(request, response)
      } else {
        AuthUtil.requireAuth(response)
      }
    }
  }

  private def defaultRepository(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain,
                                settings: SystemSettings, isUpdating: Boolean): Unit = {
    val action = request.paths match {
      case Array(_, repositoryOwner, repositoryName, _*) =>
        Database() withSession { implicit session =>
          getRepository(repositoryOwner, repositoryName.replaceFirst("\\.wiki\\.git$|\\.git$", "")) match {
            case Some(repository) => {
              val execute = if (!isUpdating && !repository.repository.isPrivate && settings.allowAnonymousAccess) {
                // Authentication is not required
                true
              } else {
                // Authentication is required
                val passed = for {
                  auth <- Option(request.getHeader("Authorization"))
                  Array(username, password) = AuthUtil.decodeAuthHeader(auth).split(":", 2)
                  account <- authenticate(settings, username, password)
                } yield if (isUpdating) {
                  if (hasDeveloperRole(repository.owner, repository.name, Some(account))) {
                    request.setAttribute(Keys.Request.UserName, account.userName)
                    true
                  } else false
                } else if(repository.repository.isPrivate){
                  if (hasGuestRole(repository.owner, repository.name, Some(account))) {
                    request.setAttribute(Keys.Request.UserName, account.userName)
                    true
                  } else false
                } else true
                passed.getOrElse(false)
              }

              if (execute) {
                () => chain.doFilter(request, response)
              } else {
                () => AuthUtil.requireAuth(response)
              }
            }
            case None => () => {
              logger.debug(s"Repository ${repositoryOwner}/${repositoryName} is not found.")
              response.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
          }
        }
      case _ => () => {
        logger.debug(s"Not enough path arguments: ${request.paths}")
        response.sendError(HttpServletResponse.SC_NOT_FOUND)
      }
    }

    action()
  }
}
