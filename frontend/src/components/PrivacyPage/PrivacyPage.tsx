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
            The controller is Kari Vänttinen. For privacy questions or requests, email{' '}
            <a href="mailto:kvanttinen@gmail.com">kvanttinen@gmail.com</a>.
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
            results. That material is sent to the AI provider configured for the service so it can
            produce an answer. Production is documented as using OpenAI for chat. The application
            can also be configured for other chat providers, so contact the controller if you need
            the current provider confirmed.
          </p>
          <p>
            Strategy-document search queries are sent to OpenAI to create embeddings even when a
            different chat provider is configured. We have not verified provider-specific region,
            retention, training or transfer terms for publication, so this notice does not promise
            EU-only processing, no provider retention, no training use or particular contractual
            safeguards. These services may process data outside the EEA; contact the controller for
            the arrangements applying to your request.
          </p>
        </section>

        <section>
          <h2>Where data is handled</h2>
          <p>
            Supabase provides authentication and database storage. The application is hosted on
            Fly.io in Paris. This does not mean that every supplier or every processing operation
            remains in the EU.
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
              Hosting and application infrastructure also create ordinary operational logs. These
              may include IP addresses, request details, timestamps and errors. Their retention
              follows the relevant service configuration; the 14-day period above applies only to
              diagnostic chat traces.
            </li>
            <li>
              Conversation history, account and profile records, invite records and usage records
              currently have no automatic expiry configured.
            </li>
            <li>You can remove shortlist entries in the application.</li>
          </ul>
          <p>
            Removing your FPL entry ID from Settings does not delete earlier conversations or
            diagnostic traces in which FPL data was already used. There is currently no self-service
            account deletion or data export.
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
            the controller above. We may ask for the minimum information needed to verify your
            identity. We normally respond within one month, but the law allows that period to be
            extended in some cases. A request is not a promise of deletion where data must be
            retained or an exception applies.
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
