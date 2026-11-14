import classes from './PrivacyPage.module.css';

export default function PrivacyPage() {
  return (
    <div className={classes.page}>
      <header className={classes.header}>
        <a className={classes.brand} href="/">
          FPL Sidekick
        </a>
        <a href="/app">Open the app</a>
      </header>

      <main className={classes.content}>
        <h1>Privacy notice</h1>
        <p>
          This notice explains what FPL Sidekick currently handles. It does not claim that the
          service has been independently assessed for GDPR compliance.
        </p>

        <section>
          <h2>Who is responsible</h2>
          <p>
            The controller is {import.meta.env.VITE_PRIVACY_CONTROLLER_NAME}. For privacy questions
            or requests, email{' '}
            <a href={`mailto:${import.meta.env.VITE_PRIVACY_CONTACT_EMAIL}`}>
              {import.meta.env.VITE_PRIVACY_CONTACT_EMAIL}
            </a>
            .
          </p>
        </section>

        <section>
          <h2>What we use and why</h2>
          <ul>
            <li>
              <strong>Account and service.</strong> Supabase Authentication handles your email and
              password. We use account, profile, conversation, shortlist and usage data to provide
              the service. We rely on performance of our contract with you for this processing.
            </li>
            <li>
              <strong>Security, quotas, testing and diagnostics.</strong> We process your IP address
              to rate-limit requests. We also record usage and may retain chat diagnostic traces to
              protect and operate the service, enforce limits and investigate faults. We rely on our
              legitimate interests in running a secure and reliable service.
            </li>
            <li>
              <strong>Optional FPL team connection.</strong> You may save an FPL entry ID in your
              profile. We use it to fetch public team, squad, mini-league and opponent-team data
              from Fantasy Premier League. Results are cached for minutes and may be shared with the
              AI provider when relevant to your request. You can remove the ID in Settings.
            </li>
          </ul>
          <p>
            There is no optional analytics or advertising tracking, and no consent checkbox for the
            storage needed to run the service.
          </p>
        </section>

        <section>
          <h2>Chats and AI providers</h2>
          <p>
            A chat request can include your recent or saved conversation context and relevant tool
            results. That material is sent to Google Gemini through the Gemini Developer API so it
            can produce an answer. This is an API-key integration, not Vertex AI.
          </p>
          <p>
            The Gemini project has Cloud Billing linked. Google’s published{' '}
            <a href="https://ai.google.dev/gemini-api/terms">Gemini API terms</a> say paid-service
            prompts and responses are not used to improve its products and are processed under its
            Data Processing Addendum. Developer Generate Content logging is disabled and the project
            currently has no logging datasets. These controls do not disable Google’s separate{' '}
            <a href="https://ai.google.dev/gemini-api/docs/usage-policies">abuse-monitoring logs</a>
            , which Google says retain prompts, context and outputs for 55 days for policy
            enforcement and certain legal or regulatory disclosures. We do not make a retrospective
            promise about earlier feedback sharing.
          </p>
          <p>
            OpenAI is used only to create embeddings for strategy-document searches; it does not
            generate the chat answer. OpenAI’s published{' '}
            <a href="https://platform.openai.com/docs/guides/your-data">API data policy</a> says API
            data is not used to train or improve its models unless the customer opts in. The owner
            disabled sharing for the FPL use case after the dashboard had shown all three sharing
            categories enabled, but did not record whether the change was organisation-wide or a
            project exclusion. API call logging is disabled and the project uses Global residency.
            OpenAI says default abuse-monitoring logs may contain submitted data and are retained
            for up to 30 days, with legal and safety exceptions. Neither provider is described here
            as zero-retention or EU-only.
          </p>
        </section>

        <section>
          <h2>Other providers and locations</h2>
          <p>
            Supabase provides authentication and database storage on its Free plan. The project’s
            selected region is Ireland (eu-west-1). The application runs on Fly.io in Paris. Resend
            Free delivers account confirmation emails, primarily processing that data in the United
            States. These locations do not mean that every supplier, support operation or
            subprocessor remains in the EU.
          </p>
          <p>
            Google’s paid Gemini terms and the public{' '}
            <a href="https://supabase.com/legal/dpa">Supabase</a> and{' '}
            <a href="https://resend.com/legal/dpa">Resend</a> service terms incorporate their
            published data-processing terms and international-transfer safeguards. This describes
            those public contracts; it is not a blanket guarantee about every provider account or
            proof of overall compliance. Fly.io provides its DPA through its{' '}
            <a href="https://fly.io/documents">compliance documents page</a>; correction of this
            project’s customer fields is still pending.
          </p>
          <p>
            If you connect an FPL entry, the service also reads public data from the official
            Fantasy Premier League API, including squad, league and opponent-team information. That
            source is separate from the account and AI providers above.
          </p>
        </section>

        <section>
          <h2>Email delivery</h2>
          <p>
            Resend documents 30-day retention for email content and metadata, delivery events, logs
            and metrics on its Free plan. Open and click tracking are disabled for the sending
            domain. This provider period is separate from our application-log policy.
          </p>
        </section>

        <section>
          <h2>How long data is kept</h2>
          <ul>
            <li>
              Diagnostic chat traces, including prompts, tool results and fault information, are
              removed after 14 days by startup and hourly cleanup.
            </li>
            <li>
              The approved maximum for ordinary application logs is 14 days. Fly.io publicly
              documents about seven days of searchable application logs, and we use no external
              error-monitoring or log-export service. Supplier security, abuse and email-delivery
              records have their own periods described in this notice.
            </li>
            <li>
              Conversations are currently not deleted automatically. The approved policy is to keep
              them for the account’s lifetime, with no 365-day or 90-day expiry, and delete them on
              account erasure. Account-erasure tooling is not implemented yet.
            </li>
            <li>
              Usage records currently have no automatic expiry. The approved but unimplemented
              policy is 90 days, extended whenever needed to protect the quota window that is still
              active.
            </li>
            <li>
              There is no automatic deletion of inactive accounts during testing. You can remove
              shortlist entries and your saved FPL entry ID in the application.
            </li>
          </ul>
          <p>
            Removing your FPL entry ID from Settings does not delete earlier conversations or
            diagnostic traces in which FPL data was already used. There is currently no self-service
            account deletion, data export or safe automated erasure process. Supabase Free does not
            provide the documented paid-plan daily backup entitlement, and we currently operate no
            separate database backup; an encrypted daily backup with seven-day retention is an
            approved target, not an active feature.
          </p>
        </section>

        <section>
          <h2>Browser storage</h2>
          <p>
            Supabase Authentication stores your sign-in session and tokens in local storage. Mantine
            stores your theme choice there too. The storage notice records “Got it” in session
            storage only, so it lasts for the current browser tab and is not consent. If storage is
            blocked, the acknowledgement is kept only in memory until the page reloads.
          </p>
        </section>

        <section>
          <h2>Your choices and rights</h2>
          <p>
            Depending on the law and circumstances, you may ask for access, correction, erasure,
            restriction or portability, or object to processing based on legitimate interests. Email
            the controller above. We ask only for the minimum information needed to verify your
            identity and normally respond within one month; the law permits extensions in some
            cases. Export and erasure tooling is still being built, so this request route must not
            be confused with an instant or self-service deletion feature.
          </p>
          <p>
            The approved erasure policy removes the account identity from its invite redemption but
            retains a non-reusable consumed-place record so deleting an account cannot reopen a
            limited invitation. Provider security, abuse or delivery records may also remain for
            their stated periods or where another legal exception applies.
          </p>
          <p>
            You may complain to the Finnish Data Protection Ombudsman at{' '}
            <a href="https://tietosuoja.fi/en/home">tietosuoja.fi/en</a>.
          </p>
        </section>

        <section>
          <h2>Automated advice</h2>
          <p>
            FPL Sidekick does not make solely automated decisions with legal or similarly
            significant effects. Its fantasy-football advice is not guaranteed to be correct.
          </p>
        </section>
      </main>
    </div>
  );
}
