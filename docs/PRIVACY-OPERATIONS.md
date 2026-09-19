# Privacy operations and retention proposals

Status: research and proposed operating procedure only. Nothing in this document activates deletion,
changes a supplier setting, accepts a contract, or proves GDPR compliance. Owner approval and the
account evidence listed below are required before the proposals become policy or code.

Last checked: 2026-09-19. Sources are the suppliers' public documents, not the project's private
account dashboards.

## Processing inventory

| Data | Purpose and location | Current lifecycle | Proposed lifecycle (not active) |
|---|---|---|---|
| Supabase Auth account (email, password verifier, session/security metadata) | Authentication in Supabase | No project-defined expiry; current SMTP delivery configuration is not visible in the repository | Keep while the account is active. Owner must decide an inactivity period before one is published. Delete with an approved account-erasure request, subject to documented exceptions and backup expiry. |
| `fpl_user_profile` (user UUID, optional FPL entry ID, quota override) | Account preferences and service operation in Supabase Postgres | FPL entry ID can be removed in Settings; otherwise no automatic expiry | Keep until removed or the account is erased. |
| `fpl_conversation` and `fpl_message` | Saved chat history in Supabase Postgres; relevant context and tool output are sent to the configured AI provider | No automatic expiry | Delete a conversation and its messages 90 days after that conversation's last activity (`updated_at`). This is a proposal, not an implemented promise. |
| `fpl_chat_trace` | Administrator-only test diagnostics: prompts, tool arguments/results, timings, usage and output | Startup and hourly cleanup after 14 days | Keep the existing 14-day rule. Verify cleanup monitoring and backup effects before presenting it as end-to-end erasure. |
| `fpl_usage_event` | Quota enforcement and token/cost audit; deliberately survives conversation deletion | No automatic expiry | Keep every row through the quota window in which it can be counted, then until the later of that window's end and 90 days after the event. The off-season window can exceed 90 days. Never replace this with a simple “older than 30/90 days” delete: it could refill an active off-season allowance. |
| `fpl_player_shortlist` | Account-owned shortlist | User can remove entries; no account-wide expiry | Keep until the user removes an entry or the account is erased. |
| `fpl_invite_redemption` and `fpl_invite_code` | Enforce the global/per-code account caps and retain the benefit attached to signup | No automatic expiry; codes are disabled, not deleted | Keep a redemption while its account exists. On account erasure, remove the link to the user but preserve a non-releasable consumed-place tombstone and the code's `uses` invariant. The current nullable row also means “burnt place” and the admin release action deletes it, so this proposal needs an explicit schema/state design before use. Never simply delete redemption rows or decrement `uses`: that would make invite places reusable and break the global cap. Keep code rows disabled rather than deleting them. |
| IP address and request/error data | In-memory request rate limiting and ordinary application/hosting logs | Limiter counters use time windows, but IP keys can remain until reuse, map clearing or process restart; platform log retention has not been verified in the account | Target no more than 14 days for ordinary logs. Fly publicly documents about 7 days of searchable app logs, already inside that target, but verify the active account and that no log shipper or other sink exists. Do not log chat bodies or credentials. |
| Optional FPL team/squad data | Public FPL API data used for team-aware answers | FPL entry ID persists in profile; squad/league caches are in process for minutes, bootstrap data for six hours; relevant results can remain in chats/traces | Keep only under the lifecycles of the profile, chat and trace that contain it. Removing the profile ID does not retroactively erase those copies. |
| Player/statistics/schedule/strategy data | Product reference data, generally not account-owned | Source-specific sync retention | Outside an account DSAR unless a user placed personal data into free text or a record is otherwise linkable to them. |
| Confirmation-email delivery data | Supabase Auth plus the SMTP provider configured in the Supabase dashboard | Repository history records Resend setup, but this task did not inspect the current dashboard, provider logs or retention | Treat provider, region, DPA and retention as unknown until dashboard and provider-account evidence are saved. Do not state a period publicly yet. |

The 90-day periods are proposed balances: they allow a user to continue recent chats and allow a
bounded operational/cost investigation, while avoiding indefinite retention. They are not derived
from a supplier default and are not active. The owner must approve or replace them and approve the
inactivity rule for accounts.

## Supplier findings: public terms versus this project

### OpenAI API

Public facts:

- OpenAI says API data is not used to train or improve its models unless the customer explicitly
  opts in: <https://platform.openai.com/docs/guides/your-data>.
- The same page says default abuse-monitoring logs can contain prompts, responses and derived
  metadata and are retained for up to 30 days, except where longer retention is required by law or
  reasonably necessary to protect OpenAI's services or a third party. Zero Data Retention and
  Modified Abuse Monitoring require eligibility, approval and additional requirements; they are
  not ordinary unchecked defaults.
- For `/v1/chat/completions`, the public endpoint table says no application-state retention apart
  from listed exceptions; abuse-monitoring retention is 30 days. Embeddings likewise show no
  application state and 30-day abuse-monitoring retention. Prompt caching can retain encrypted
  tensors in GPU-local storage for up to 24 hours.
- OpenAI's current public DPA describes OpenAI as processor, assistance with data-subject requests,
  deletion/return after termination, subprocessors, and SCCs or an adequacy decision for relevant
  EEA/Swiss transfers: <https://openai.com/policies/data-processing-addendum>. Public text is not
  evidence that the correct customer accepted it or that a particular project has EU residency or
  a retention control enabled.

Project facts and gaps:

- `application.yml` currently selects OpenAI chat only when `SPRING_AI_PROVIDER=openai`; OpenAI
  embeddings are configured independently for strategy search. Production has previously been
  documented as OpenAI chat, but the live provider must be confirmed from deployment settings.
- The account's training opt-in, retention-control approval, project-level override, API endpoint,
  project geography and DPA/customer identity were not inspected. Therefore the project must not
  promise “no training”, Zero Data Retention, EU-only processing or signed transfer terms as an
  account guarantee. The public no-training-unless-opt-in rule is accurately stated only with this
  qualification.

### Supabase

Public facts:

- The Supabase DPA forms part of its agreement, identifies Supabase as processor, permits processing
  where it or subprocessors have facilities subject to transfer terms, incorporates SCCs where
  applicable, and describes return/deletion after termination:
  <https://supabase.com/legal/dpa>.
- The current subprocessor source and update subscription are at
  <https://supabase.com/legal/customer-resources/subprocessor-list>.
- Backup availability depends on plan. Supabase documents daily backups for Pro (7 days), Team
  (14 days), and Enterprise (up to 30 days); the Free plan is told to make its own exports:
  <https://supabase.com/docs/guides/platform/backups>.
- Log retention also depends on plan: <https://supabase.com/docs/guides/observability/logs>.
  The public pricing page currently lists 7-day logs/backups for Pro and 28-day logs/14-day backups
  for Team: <https://supabase.com/pricing>.

Project facts and gaps:

- Supabase supplies Auth and the shared PostgreSQL database. The database contains this project's
  user data and unrelated Tapas tables, so a broad database deletion is unsafe.
- This task did not inspect the project's plan, database/Auth region, backup/PITR panel, log drains,
  support exports, DPA acceptance/customer identity, current subprocessors or SMTP settings.
  Public plan descriptions do not establish which periods or transfer mechanism apply here.

### Fly.io

Public facts:

- The repository configures the application primary region as `cdg` (Paris). That says where its
  Machine is requested, not where every Fly control-plane, log, support or subprocessor operation
  occurs.
- Fly's public privacy statement says information it collects is generally stored and processed in
  the United States and describes international safeguards:
  <https://fly.io/legal/privacy-policy/>.
- Fly offers a pre-signed DPA that becomes active when the customer signs it. The correct official
  route is the authenticated compliance-documents page, <https://fly.io/documents>. The guessed
  URL <https://fly.io/legal/data-processing-addendum/> returns 404 and must not be cited as a DPA.
- Fly's subprocessor list is <https://fly.io/legal/sub-processors>.
- Fly documents searchable app-log retention as about 7 days:
  <https://fly.io/docs/monitoring/search-logs> and
  <https://fly.io/docs/monitoring/logs-api-options>. Exporting logs creates a separate sink with
  its own retention.
- Fly volume snapshots default to five days and can be configured from 1 to 60 days:
  <https://fly.io/docs/volumes/overview>. The current app configuration declares no Fly volume;
  this does not establish retention of platform backups or logs.

Project gaps: confirm the live Machine region, whether a DPA has been signed by the correct party,
whether a log shipper/sink exists, searchable-log behavior, support access and any snapshots. Do
not infer all-EU processing from `primary_region = 'cdg'`.

## Backup and deletion rule

A deletion from the live database does not rewrite an already-created backup. The operating rule
should be:

1. Remove the data from active systems after an approved request.
2. Record the minimum deletion evidence outside the restorable dataset: request identifier,
   affected internal user UUID, scope, completion time, backup systems affected and the date by
   which each backup should expire. Restrict access and give this evidence its own short audit
   period approved by the owner.
3. Do not restore an old backup directly into service. Restore into isolation, then reapply every
   deletion completed after the restore point before allowing traffic or exports.
4. If a supplier cannot selectively delete immutable backups, keep them inaccessible for ordinary
   use, let them expire under the verified supplier period, and use restored data only for disaster
   recovery followed by the re-deletion step.
5. Verify Auth, database backups/PITR, logs, downloaded exports and support attachments separately.
   Deleting a Postgres row does not prove deletion from any of them.

No backup period should be published until the active plan and settings have been captured.

## Manual data-subject request workflow

This is a practical workflow, not legal advice or an automated feature.

1. **Receive and timestamp.** Route requests to the privacy contact, record the received date,
   requested right/scope and a non-sensitive case identifier. GDPR Article 12's ordinary response
   period is one month: <https://eur-lex.europa.eu/eli/reg/2016/679/art_12/oj>.
2. **Verify proportionately.** Prefer a request made from a signed-in account or a reply from the
   account email. Ask only for additional information needed to resolve a genuine mismatch. Do not
   routinely request identity documents for a fantasy-football account; if one is exceptionally
   necessary, do not retain a copy longer than verification requires.
3. **Clarify scope without delaying obvious work.** Identify access, correction, objection,
   restriction, portability or erasure; note any legal exception. Tell the requester promptly if
   more time is lawfully needed and why.
4. **Resolve the account key.** Use the Supabase Auth dashboard/admin tooling to map the verified
   account to its JWT `sub` UUID. Never search the shared database by guessed email: project-owned
   tables intentionally store only the UUID.
5. **Inventory by owner key.** Review Auth; `fpl_user_profile`; owned conversations and messages;
   chat traces; usage events; shortlist; invite redemption; and any case/log/export material.
   Conversation message ownership is through the owned conversation, while usage rows deliberately
   have no foreign key. Check free-text chats/traces for third-party data before disclosure.
6. **Export safely.** Produce a common, readable format, explain source/retention limits, and send it
   through an authenticated or otherwise suitably protected channel. Do not include secrets,
   internal security material, other users' data or unrelated shared-database rows.
7. **Correct, restrict or erase with a reviewed runbook.** There is no approved generic deletion
   SQL. Use explicit project table names and owner predicates in a reviewed transaction; preview
   counts, take evidence, and verify afterward. Handle the invite consumed-place tombstone and
   counter invariant separately. Delete/revoke the Auth account only at the ordered point in the
   runbook. Never run a schema-wide or email-based delete against the shared Supabase database.
8. **Suppliers and caches.** Clear applicable application caches by restart/invalidation if needed.
   Ask a processor for assistance where data cannot be handled through project controls. Provider
   abuse/security logs may remain under published exceptions and are not directly queryable by this
   project.
9. **Backups.** Add the completed deletion to the restore/re-deletion register and state the
   verified final backup-expiry date in the response; do not claim immediate physical erasure from
   immutable backups.
10. **Respond and close.** Describe what was supplied or changed, what remains and why, complaint
    rights, and any backup/vendor limitation. Store only the minimal case evidence for the approved
    audit period.

## Owner decisions and account evidence required

Before implementing or strengthening the public notice, the owner must:

1. Approve or replace: 90 days since last activity for conversations; the usage rule (quota window
   plus a 90-day event tail); 14 days maximum for ordinary logs; and an account inactivity period.
2. Decide the invite-erasure tombstone design and audit period. It must distinguish consumed
   account places from releasable burnt reservations and preserve `fpl_invite_code.uses` plus the
   global cap.
3. Decide whether to request OpenAI retention controls/data residency and confirm the training
   opt-in is off; save organization/project screenshots or exports, endpoint and date checked.
4. Confirm the contracting customer and DPA applicability for OpenAI and Supabase; sign/download
   Fly's DPA if required; record the transfer mechanism and current subprocessor lists.
5. Capture Supabase project region, plan, daily-backup/PITR retention, log retention/drains and Auth
   log behavior. Confirm whether database backups cover Auth and how deletion after restore is
   operationalized.
6. Capture Fly live region, searchable-log window, external log sinks, snapshots/volumes and support
   settings. A repository region is not account evidence.
7. Confirm the current Supabase SMTP provider, account/region, delivery-log retention, DPA and
   transfer terms. Repository history mentioning Resend is not current dashboard evidence.
8. Approve an access-controlled DSAR case register, response channel and restore/re-deletion
   procedure; then test them on synthetic data before handling a real request.

Until those decisions are made, the existing notice should continue to describe unimplemented
retention as unconfigured and supplier account controls as unverified.
