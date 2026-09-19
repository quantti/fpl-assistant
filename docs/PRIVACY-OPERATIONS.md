# Privacy operations and retention decisions

Status: research, approved decisions and proposed operating procedure only. Nothing in this document
activates deletion, changes a supplier setting, accepts a contract, or proves GDPR compliance.
Approved retention decisions remain implementation work unless this document says they already exist.

Last checked: 2026-09-19. Sources are the suppliers' public documents, not the project's private
account dashboards.

## Owner decisions (implementation pending)

These decisions supersede the corresponding proposals below; unimplemented lifecycle rules are not active yet.

- Conversations/messages: retain while the account exists and delete on account deletion.
  No age-based expiry (the owner withdrew the 365-day limit).
- Usage events: retain for 90 days after the event, and longer where required to preserve the
  active quota window. Never expire rows still counted toward that window.
- Ordinary application logs: maximum 14 days; shorter platform retention is acceptable and
  need not be extended. The owner confirms there is no external error-monitoring or log-export service;
  supplier-controlled security, abuse and delivery records have their separately stated periods.
- Inactive accounts: no automatic inactivity deletion during testing. Review continued need
  at the end of the test period; handle account-erasure requests separately.
- Invite redemption on account erasure: remove the user association and retain only a
  consumed-place record without email, FPL entry ID or user UUID. Account deletion must not
  release the invite place or reset usage counters. Implementation needs an explicit
  non-releasable state distinct from the existing releasable failed reservation.
- Gemini: owner confirms Cloud Billing is linked to the Gemini API project. This is
  owner-provided configuration evidence, not an independent dashboard inspection. The public
  contractual applicability and transfer terms are recorded below; customer identity still needs
  to be retained as account evidence.
- Gemini developer logging: owner checked AI Studio and reports no logs, Generate Content
  logging disabled and an empty Datasets view. No current datasets are available to share;
  historical feedback sharing has not been verified. This does not disable Google's separate
  abuse-monitoring logs.
- OpenAI: owner reports completing the sharing opt-out for the FPL use case after the
  dashboard showed all three sharing categories enabled for all projects. Whether the change
  disabled sharing organization-wide or excluded only the FPL project was not specified.
  Earlier sharing is not undone and its actual scope remains unverified.
- OpenAI API call logging: owner reports setting it to Disabled and saving, following the
  screenshot showing Enabled per call. This is separate from abuse-monitoring retention;
  no Zero Data Retention approval is established. The shown project has Global residency.
- Supabase: owner confirms Free plan and project region eu-west-1 (Ireland). This confirms
  the primary project region, not EU-only processing by every supplier or subprocessor. The owner
  confirms no self-managed backup; Supabase documents no Free-plan daily-backup entitlement.
- Backups: owner confirms no self-managed backups currently and approves a separate
  implementation task: daily encrypted backups stored separately from the live database,
  seven-day retention, a restore test, explicit scope for the database shared with Tapas,
  and reapplication of completed personal-data deletions after restore. Not activated.
  Tracked as [KAR-62](https://linear.app/quantti/issue/KAR-62), priority High, status Todo.
- Fly DPA correction deferred by owner: [KAR-63](https://linear.app/quantti/issue/KAR-63),
  Todo, normal priority. Downloaded signed DPA.pdf was inspected. Company
  Legal Name is a dash and the two customer fields were populated under the wrong headings
  (frequency text under sensitive data; processing description under transfer frequency).
  Follow up with finance@fly.io to confirm individual contracting identity, correct the fields,
  re-sign and confirm delivery/acceptance under the document's execution instructions.
  Do not mark the DPA verification complete. Keep the signed document outside git.
- Confirmation email: owner confirms Resend Free is the current SMTP provider. Resend's
  public documentation states 30-day email content/metadata, delivery-event, log and metric
  retention across Free/Pro/Scale:
  https://resend.com/docs/knowledge-base/account-quotas-and-limits#data-retention .
  Owner accepts this 30-day email-service retention as a separately disclosed period,
  distinct from our approved 14-day application-log maximum. Owner confirms Open tracking
  and Click tracking are disabled on the sending domain. DPA/transfer findings are recorded below.
- Data-subject requests: owner approves an email-based process with proportionate identity
  checks and the ordinary one-month response period. Safe admin export/erasure tooling still
  needs implementation; no self-service UI is required for the initial process.
- Privacy contact migration planned: [KAR-64](https://linear.app/quantti/issue/KAR-64), Todo,
  High priority, will provision/verify admin@fpl-sidekick.com before changing public contact
  references. Until then kvanttinen@gmail.com remains the published working contact; preserve
  the old channel during transition. Related inbound-mail issue: KAR-43.
- Administrative access: owner confirms no other people have access to production Supabase,
  Fly.io or application administration, including through the shared Tapas project. This
  records human access only; service credentials and automated integrations require separate
  protection and are not implied to be absent.
- Administrator authentication: owner reports both Supabase and Fly.io are accessed through
  Google sign-in and that MFA is enabled on the Google account. Other provider accounts,
  recovery methods, API keys and existing sessions were not independently audited.
- Personal-data incidents: owner approves Kari Vänttinen as responsible person and the
  lightweight response procedure below. This is an operating procedure, not an assertion
  that an incident has occurred or that every outage is reportable.
- External observability: owner confirms no additional error-monitoring or log-export
  services (such as Sentry, Grafana Cloud or Better Stack). This does not establish the
  retention of each hosting/authentication provider's own operational logs.
- OpenAI DPA execution deferred: [KAR-65](https://linear.app/quantti/issue/KAR-65), Todo,
  normal priority. The form requires company legal name/address with no visible individual
  option. Confirm with OpenAI support whether Kari Vänttinen may contract using personal legal
  name/address, then review/execute and retain evidence privately. Do not invent a company,
  submit placeholders or mark contract verification complete while this is pending.
- These confirmations do not activate the pending retention, deletion, backup or DSAR tooling.

## Processing inventory

| Data | Purpose and location | Current lifecycle | Approved/target lifecycle (not active unless stated) |
|---|---|---|---|
| Supabase Auth account (email, password verifier, session/security metadata) | Authentication in Supabase | No project-defined expiry | Keep while the account exists and delete on an approved account-erasure request, subject to documented exceptions and backup expiry. Do not auto-delete inactive accounts during testing; review this at the end of testing. |
| `fpl_user_profile` (user UUID, optional FPL entry ID, quota override) | Account preferences and service operation in Supabase Postgres | FPL entry ID can be removed in Settings; otherwise no automatic expiry | Keep until removed or the account is erased. |
| `fpl_conversation` and `fpl_message` | Saved chat history in Supabase Postgres; relevant context and tool output are sent to Google Gemini for chat | No automatic expiry | Owner approved: retain while the account exists and delete on account deletion, with no age-based expiry. Account deletion is not implemented yet. |
| `fpl_chat_trace` | Administrator-only test diagnostics: prompts, tool arguments/results, timings, usage and output | Startup and hourly cleanup after 14 days | Keep the existing 14-day rule. Verify cleanup monitoring and backup effects before presenting it as end-to-end erasure. |
| `fpl_usage_event` | Quota enforcement and token/cost audit; deliberately survives conversation deletion | No automatic expiry | Keep every row through the quota window in which it can be counted, then until the later of that window's end and 90 days after the event. The off-season window can exceed 90 days. Never replace this with a simple “older than 30/90 days” delete: it could refill an active off-season allowance. |
| `fpl_player_shortlist` | Account-owned shortlist | User can remove entries; no account-wide expiry | Keep until the user removes an entry or the account is erased. |
| `fpl_invite_redemption` and `fpl_invite_code` | Enforce the global/per-code account caps and retain the benefit attached to signup | No automatic expiry; codes are disabled, not deleted | Keep a redemption while its account exists. On account erasure, remove the link to the user but preserve a non-releasable consumed-place tombstone and the code's `uses` invariant. The current nullable row also means “burnt place” and the admin release action deletes it, so this proposal needs an explicit schema/state design before use. Never simply delete redemption rows or decrement `uses`: that would make invite places reusable and break the global cap. Keep code rows disabled rather than deleting them. |
| IP address and request/error data | In-memory request rate limiting and ordinary application/hosting logs | Limiter counters use time windows, but IP keys can remain until reuse, map clearing or process restart; platform log retention has not been verified in the account | Approved maximum 14 days for ordinary application logs. Fly publicly documents about 7 days of searchable app logs, already inside that maximum. The owner confirms no external log sink. Do not log chat bodies or credentials. |
| Optional FPL team/squad data | Public FPL API data used for team-aware answers | FPL entry ID persists in profile; squad/league caches are in process for minutes, bootstrap data for six hours; relevant results can remain in chats/traces | Keep only under the lifecycles of the profile, chat and trace that contain it. Removing the profile ID does not retroactively erase those copies. |
| Player/statistics/schedule/strategy data | Product reference data, generally not account-owned | Source-specific sync retention | Outside an account DSAR unless a user placed personal data into free text or a record is otherwise linkable to them. |
| Confirmation-email delivery data | Supabase Auth plus Resend Free | Owner confirms 30-day Resend email content/metadata, delivery-event, log and metric retention; open/click tracking is disabled | Owner accepts Resend's 30-day provider retention as distinct from the 14-day application-log maximum. Account deletion/DSAR handling and any provider exceptions still need an implemented runbook. |

The approved conversation, usage-event, inactivity and ordinary-log rules above are not active merely
because they are documented. Supplier-controlled abuse, security and delivery retention is separate
from the project's own retention and must not be described as if the project could shorten it.

## Supplier findings: public terms versus this project

### Google Gemini Developer API (chat)

Public facts:

- The current Gemini API Additional Terms distinguish paid and unpaid services:
  <https://ai.google.dev/gemini-api/terms>. For paid services, Google says prompts and responses
  are not used to improve its products and are processed under the linked Data Processing
  Addendum. It may transiently store or cache them in countries where Google or its agents have
  facilities.
- The same terms say an API client made available to users in the EEA, Switzerland or UK may use
  only paid services. They also say that, when the developer is in one of those jurisdictions,
  the paid-services data-use terms apply even to AI Studio and unpaid Gemini API quota. This means
  a blanket statement that all unpaid Gemini traffic is used for product improvement would be
  wrong. Outside that regional exception, the unpaid-services terms allow submitted content and
  responses to be used to provide, improve and develop products and machine-learning technologies,
  with possible human review.
- Google's abuse-monitoring page says prompts, contextual information and outputs are retained for
  55 days for policy enforcement and required legal or regulatory disclosures; flagged content
  can be reviewed by authorised employees:
  <https://ai.google.dev/gemini-api/docs/usage-policies>. It says those logs are not used to train
  or fine-tune models other than models specifically used for policy enforcement.
- Billing-enabled projects can separately opt in to developer-owned API logging. Those logs
  default to a maximum 55-day retention, configurable to 7, 14, 28 or 55 days, while datasets can
  retain selected logs without a set period. Sharing datasets or feedback with Google is optional
  and can permit product improvement and model training:
  <https://ai.google.dev/gemini-api/docs/logs-policy>.
- Google's Gemini Developer API zero-retention page says ordinary paid use still has limited abuse
  logging and directs workloads requiring guaranteed zero retention or enterprise data-processing
  arrangements to Vertex AI: <https://ai.google.dev/gemini-api/docs/zdr>.
- DPA applicability is incorporated rather than a separate signature claim. The paid-service clause
  in the Additional Terms says paid prompts/responses are processed under the Google DPA. DPA section
  4.1 applies it where the service agreement incorporates it by reference, and Google's official
  service list expressly names “Gemini API Paid Services”:
  <https://business.safety.google/processorterms/> and
  <https://business.safety.google/services/>. The DPA's Terms Effective Date can arise when the
  customer clicks to accept it or otherwise agrees; this evidence does not prove a handwritten or
  separately executed DPA.
- DPA Appendix 3A section 7 makes the transfer basis conditional: no extra mechanism for an Adequate
  Country; Google's adopted transfer solution (including the EU-US DPF for a certified US Google
  entity) for a restricted transfer; applicable SCCs if no such solution is available. It does not
  promise EU-only processing, and the paid terms expressly permit transient storage/cache wherever
  Google or its agents maintain facilities.

Project facts and gaps:

- The owner confirms that current production chat uses Google Gemini. The checked-in Spring AI
  `google-genai` configuration supplies `GEMINI_API_KEY` and does not configure Vertex AI's
  `project-id`, `location` or `vertex-ai` switch. Spring AI 2.0.0 therefore builds the Google GenAI
  client with an API key, which is the Gemini Developer API path, not Vertex AI. This conclusion
  inspects configuration and the installed SDK/autoconfiguration only, not the secret value.
- The owner confirms active Cloud Billing for the project, developer Generate Content logging off,
  no logs and empty datasets. On that evidence the Gemini API calls are Paid Services and the DPA is
  incorporated through the terms above; no separate Google DPA signature is required by those public
  clauses. Record the contracting customer/address as account evidence, but do not confuse a logging
  toggle or empty dataset with contractual acceptance.
- The optional logging/dataset controls do not disable Google's separate abuse-monitoring retention.
  Historical feedback sharing was not established. Do not promise zero retention, EU-only processing
  or a data-region commitment.

### OpenAI API (strategy-search embeddings)

Public facts:

- OpenAI says API data is not used to train or improve its models unless the customer explicitly
  opts in: <https://platform.openai.com/docs/guides/your-data>.
- The same page says default abuse-monitoring logs can contain prompts, responses and derived
  metadata and are retained for up to 30 days, except where longer retention is required by law or
  reasonably necessary to protect OpenAI's services or a third party. Zero Data Retention and
  Modified Abuse Monitoring require eligibility, approval and additional requirements; they are
  not ordinary unchecked defaults. Its endpoint table says embeddings have no application-state
  retention and are subject to the abuse-monitoring retention.
- OpenAI's current public DPA describes OpenAI as processor, assistance with data-subject requests,
  deletion/return after termination and subprocessors. Section 4.1 uses SCCs or an adequacy decision
  for relevant EEA/Swiss onward transfers, while section 4.2 incorporates SCCs plus the UK Addendum
  for UK data: <https://openai.com/policies/data-processing-addendum>. It does not state an EU-US DPF
  basis, so none should be inferred.
- The DPA page says it supplements and is incorporated into the Services Agreement and that clicking
  “I agree,” accepting an order form, or using the services agrees to the agreement. OpenAI's own
  enterprise-privacy FAQ nevertheless instructs API customers to complete its DPA form “to execute a
  DPA”: <https://openai.com/api-data-privacy>. Because the public routes are not phrased consistently,
  retain an executed customer-specific copy through that official form rather than claiming a
  signature or relying only on an account setting.

Project facts and gaps:

- OpenAI embeddings are configured independently of the chat provider. Strategy-search query text
  is sent to OpenAI for embedding; chat generation is not currently sent to OpenAI.
- The owner reports opting the FPL use case out after all three sharing categories had appeared
  enabled, but whether that changed the organization or only one project is unclear. Earlier sharing
  is not reversed. Confirm the resulting scope in the account; until then do not make an
  account-specific no-sharing claim beyond OpenAI's default no-training rule.
- The owner reports API call logging disabled and Global residency. This is not Zero Data Retention
  approval, does not override default abuse monitoring, and is not EU residency. DPA execution and
  the contracting customer remain to be evidenced; do not invent a signature.

### Supabase

Public facts:

- The Supabase DPA expressly supplements and forms part of its Terms of Service and is effective on
  the agreement's effective date; it is not presented as a separately signed addendum. It identifies
  Supabase as processor and describes return/deletion after termination:
  <https://supabase.com/legal/dpa>.
- DPA sections 6.1 and 12 permit processing where Supabase or subprocessors maintain facilities,
  while storing and primarily processing region-directed data in that region. SCCs apply only to the
  transfers described in section 12.1; section 12.2 says accepting the agreement has the same effect
  as signing those SCCs. The DPA names Supabase Pte. Ltd in Singapore as importer. This is a
  conditional SCC safeguard, not EU-only processing and not evidence of a separate signature or DPF.
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
- The owner confirms Free plan and eu-west-1 (Ireland). That establishes the selected storage/primary
  processing region, not that every Supabase/support/subprocessor operation remains in the EEA.
  There are no owner-managed backups yet; the approved seven-day separate encrypted backup is pending
  KAR-62. Record the contracting customer and retain the applicable terms as evidence, but no separate
  Supabase DPA execution is indicated by the public clause.

### Resend (confirmation email)

Public facts:

- Resend's DPA is attached to and incorporated into its Terms of Service and says it becomes legally
  binding when the customer enters the agreement or executes the DPA. Section 12 says signature
  blocks are for reference and an executed version is available in the dashboard; ordinary ToS
  acceptance therefore does not require a new signature:
  <https://resend.com/legal/dpa>.
- Primary processing is in the United States. Sections 6.1–6.5 incorporate EU SCCs/UK Addendum for
  transfers not governed by an adequacy decision and section 6.6 adds supplementary measures.
  Section 11 states that Resend is certified under the EU-US DPF and UK Extension. DPF applies only
  where the transfer actually relies on that certification; SCCs remain the documented fallback,
  not a claim that every transfer uses both mechanisms simultaneously.
- The provider documents 30-day retention of email content/metadata, delivery events, logs and
  metrics for Free, Pro and Scale:
  <https://resend.com/docs/knowledge-base/account-quotas-and-limits#data-retention>.

Project facts and gaps:

- The owner confirms Resend Free, accepts that separate 30-day provider period, and confirms open and
  click tracking are off. Save the dashboard's executed DPA copy and contracting customer as evidence;
  downloading it records the incorporated agreement and does not constitute accepting a new one.

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

Project facts and gaps: production is documented in `cdg`; the owner confirms no external log sink
or additional human administrator. The downloaded DPA has invalid/misaligned customer fields, so
KAR-63 must resolve the contracting party and execution before it can be relied upon. Support access
and supplier-controlled logs remain distinct from app-log retention. Do not infer all-EU processing
from `primary_region = 'cdg'`.

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

The owner-approved target is a separately stored encrypted daily backup with seven-day retention,
restore testing and post-restore re-deletion. KAR-62 tracks implementation. Until that exists, describe
it only as a target: Supabase Free does not supply the documented Pro daily-backup entitlement, and
there is currently no owner-managed backup to which the seven-day period could apply.

## Personal-data incident response

Responsible person: Kari Vänttinen.

1. Contain the incident: block compromised access, revoke/rotate affected credentials and
   preserve necessary evidence securely without unnecessarily copying personal data.
2. Record when the incident became known, what happened, affected systems/data/people,
   likely consequences and containment/remediation actions. Assess risks to individuals.
3. Notify the competent supervisory authority without undue delay and, where feasible,
   within 72 hours of becoming aware of a personal-data breach, unless it is unlikely to
   result in a risk to individuals' rights and freedoms. Explain any delay; provide
   information in phases where necessary rather than waiting for a complete investigation.
4. Where the breach is likely to result in a high risk, inform affected individuals without
   undue delay, subject to the applicable legal exceptions. Explain practical protective steps.
5. Document the incident and notification decision even if notification is not required;
   correct the cause and review recovery. An outage or failed login alone does not establish
   a reportable personal-data breach. Restrict access to the incident record.

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

## Remaining owner actions and implementation dependencies

Approved decisions above do not need to be asked again. The genuinely remaining owner/account actions
are:

1. **OpenAI:** use OpenAI's official DPA form at <https://openai.com/api-data-privacy> and retain the
   resulting customer-specific executed copy outside git. In the dashboard, capture whether the
   sharing opt-out applies organization-wide or only to the FPL project. Do not change residency or
   request Zero Data Retention merely to gather evidence.
2. **Contract evidence:** record the contracting customer/name and address shown for Google,
   Supabase, OpenAI and Resend; download Resend's already-incorporated executed DPA from its dashboard.
   These are evidence actions, not instructions to accept or sign new terms. Google's paid Gemini DPA,
   Supabase's DPA and Resend's DPA are incorporated as described above.
3. **Fly:** resolve the invalid/misaligned DPA fields through KAR-63 before relying on it. Do not
   re-sign or claim delivery/acceptance until Fly confirms the correct individual contracting form.
4. **Contact and backup:** complete KAR-64 before publishing the new DSAR address, and KAR-62 before
   promising seven-day backup retention or tested restores. Keep the current email request route in
   the meantime.
5. **Implementation policy input:** approve the short retention period for the minimal DSAR/deletion
   case register before that register is built. The conversation, usage, inactivity, ordinary-log and
   invite consumed-place decisions themselves are settled.

Engineering still needs to implement and test account erasure, the non-releasable invite tombstone,
usage cleanup that protects the active quota window, backup/re-deletion handling, and safe DSAR
export/erasure. Until then, public wording must distinguish approved targets from active behavior.
