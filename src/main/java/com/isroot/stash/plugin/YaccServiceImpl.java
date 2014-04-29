package com.isroot.stash.plugin;

import com.atlassian.applinks.api.CredentialsRequiredException;
import com.atlassian.sal.api.net.ResponseException;
import com.atlassian.stash.content.Changeset;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.user.StashAuthenticationContext;
import com.atlassian.stash.user.StashUser;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * @author Sean Ford
 * @since 2014-01-14
 */
public class YaccServiceImpl implements YaccService
{
	private static final Logger log = LoggerFactory.getLogger(YaccServiceImpl.class);


	private final StashAuthenticationContext stashAuthenticationContext;
	private final ChangesetsService changesetsService;
	private final JiraService jiraService;

	public YaccServiceImpl(StashAuthenticationContext stashAuthenticationContext, ChangesetsService changesetsService,
						   JiraService jiraService)
	{
		this.stashAuthenticationContext = stashAuthenticationContext;
		this.changesetsService = changesetsService;
		this.jiraService = jiraService;
	}

	@Override
	public List<String> checkRefChange(Repository repository, Settings settings, RefChange refChange)
	{
		log.debug("checking ref change refId={} fromHash={} toHash={} type={}", refChange.getRefId(), refChange.getFromHash(),
				refChange.getToHash(), refChange.getType().toString());

		List<String> errors = Lists.newArrayList();

		Set<YaccChangeset> changesets = changesetsService.getNewChangesets(repository, refChange);

		for (YaccChangeset changeset : changesets)
		{
			if(settings.getBoolean("excludeMergeCommits", false) && changeset.getParentCount() > 1)
			{
				log.debug("skipping commit {} because it is a merge commit", changeset.getId());

				continue;
			}

			for(String e : checkChangeset(settings, changeset))
			{
				errors.add(String.format("%s: %s: %s", refChange.getRefId(), changeset.getId(), e));
			}
		}

		return errors;
	}

	private List<String> checkChangeset(Settings settings, YaccChangeset changeset)
	{
		log.debug("checking commit id={} name={} email={} message={}", changeset.getId(),
				changeset.getCommitter().getName(), changeset.getCommitter().getEmailAddress(),
				changeset.getMessage());

		List<String> errors = Lists.newArrayList();

		errors.addAll(checkAuthorEmail(settings, changeset));
		errors.addAll(checkAuthorName(settings, changeset));
		errors.addAll(checkCommitMessageRegex(settings, changeset));

		// Checking JIRA issues might be dependent on the commit message regex, so only proceed if there are no errors.
		if(errors.isEmpty())
		{
			errors.addAll(checkJiraIssues(settings, changeset));
		}

		return errors;
	}

	private List<String> checkCommitMessageRegex(Settings settings, YaccChangeset changeset)
	{
		List<String> errors = Lists.newArrayList();

		String regex = settings.getString("commitMessageRegex");
		if(isNullOrEmpty(regex) == false)
		{
			Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
			Matcher matcher = pattern.matcher(changeset.getMessage());
			if(matcher.matches() == false)
			{
				errors.add("commit message doesn't match regex: " + regex);
			}
		}

		return errors;
	}

	private List<IssueKey> extractJiraIssuesFromCommitMessage(Settings settings, YaccChangeset changeset)
	{
		String message = changeset.getMessage();

		// If a commit message regex is present, see if it contains a group 1 that can be used to located JIRA issues.
		// If not, just ignore it.
		String regex = settings.getString("commitMessageRegex");
		if(isNullOrEmpty(regex) == false)
		{
			Pattern pattern = Pattern.compile(regex);
			Matcher matcher = pattern.matcher(message);
			if(matcher.matches() && matcher.groupCount() > 0)
			{
				message = matcher.group(1);
			}
		}

        final List<IssueKey> issueKeys = IssueKey.parseIssueKeys(message);
		log.debug("found jira issues {} from commit message: {}", issueKeys, message);

		return issueKeys;
	}

	private List<String> checkJiraIssues(Settings settings, YaccChangeset changeset)
	{
		if (!settings.getBoolean("requireJiraIssue", false))
		{
			return Lists.newArrayList();
		}

		List<String> errors = Lists.newArrayList();

		if (!jiraService.doesJiraApplicationLinkExist())
		{
			errors.add(String.format("Unable to verify JIRA issue because JIRA Application Link does not exist"));
			return errors;
		}

		final List<IssueKey> issues;
		try {
			final List<IssueKey> extractedKeys = extractJiraIssuesFromCommitMessage(settings, changeset);
			if (settings.getBoolean("ignoreUnknownIssueProjectKeys", false))
			{
				/* Remove issues that contain non-existent project keys */
				issues = Lists.newArrayList();
				for (IssueKey issueKey : extractedKeys) {
					if (jiraService.doesProjectExist(issueKey.getProjectKey()))
					{
						issues.add(issueKey);
					}
				}
			}
			else
			{
				issues = extractedKeys;
			}
		}
		catch(CredentialsRequiredException e)
		{
			log.error("communication error while validating issues", e);
			errors.add(String.format("Unable to validate JIRA issue because there was an authentication failure when communicating with JIRA."));
			return errors;
		}
		catch(ResponseException e)
		{
			log.error("unexpected exception while trying to validate JIRA issues", e);
			errors.add(String.format("Unable to validate JIRA issues due to an unexpected exception. Please see stack trace in logs."));
			return errors;
		}

		if(issues.isEmpty() == false)
		{
			for(IssueKey issueKey : issues)
			{
				errors.addAll(checkJiraIssue(settings, issueKey));
			}
		}
		else
		{
			errors.add(String.format("No JIRA Issue found in commit message."));
		}

		return errors;
	}

	private List<String> checkJiraIssue(Settings settings, IssueKey issueKey)
	{
		List<String> errors = Lists.newArrayList();

		try
		{
			if (!jiraService.doesIssueExist(issueKey))
			{
				errors.add(String.format("%s: JIRA Issue does not exist", issueKey.getFullyQualifiedIssueKey()));
			}
			else
			{
				String jqlQuery = settings.getString("issueJqlMatcher");
				if (jqlQuery != null && !jqlQuery.isEmpty())
				{
					if (!jiraService.doesIssueMatchJqlQuery(jqlQuery, issueKey))
					{
						errors.add(String.format("%s: JIRA Issue does not match JQL Query: %s", issueKey, jqlQuery));
					}
				}
			}
		}
		catch(CredentialsRequiredException e)
		{
			errors.add(String.format("%s: Unable to validate JIRA issue because there was an authentication failure when communicating with JIRA.", issueKey.getFullyQualifiedIssueKey()));
		}
		catch(ResponseException e)
		{
			log.error("unexpected exception while trying to validate JIRA issue", e);
			errors.add(String.format("%s: Unable to validate JIRA issue due to an unexpected exception. Please see stack trace in logs.", issueKey.getFullyQualifiedIssueKey()));
		}

		return errors;
	}

	private List<String> checkAuthorEmail(Settings settings, YaccChangeset changeset)
	{
		StashUser stashUser = stashAuthenticationContext.getCurrentUser();
		final boolean requireMatchingAuthorEmail = settings.getBoolean("requireMatchingAuthorEmail", false);

		List<String> errors = Lists.newArrayList();

		log.debug("requireMatchingAuthorEmail={} authorName={} stashName={}", requireMatchingAuthorEmail, changeset.getCommitter().getEmailAddress(),
				stashUser.getEmailAddress());

		if (requireMatchingAuthorEmail && !changeset.getCommitter().getEmailAddress().equals(stashUser.getEmailAddress()))
		{
			errors.add(String.format("expected author email '%s' but found '%s'", stashUser.getEmailAddress(),
					changeset.getCommitter().getEmailAddress()));
		}

		return errors;
	}

	private List<String> checkAuthorName(Settings settings, YaccChangeset changeset)
	{
		StashUser stashUser = stashAuthenticationContext.getCurrentUser();
		final boolean requireMatchingAuthorName = settings.getBoolean("requireMatchingAuthorName", false);

		List<String> errors = Lists.newArrayList();

		log.debug("requireMatchingAuthorName={} authorName={} stashName={}", requireMatchingAuthorName, changeset.getCommitter().getName(),
				stashUser.getDisplayName());

		if (requireMatchingAuthorName && !changeset.getCommitter().getName().equals(stashUser.getDisplayName()))
		{
			errors.add(String.format("expected author name '%s' but found '%s'", stashUser.getDisplayName(),
					changeset.getCommitter().getName()));
		}

		return errors;
	}
}