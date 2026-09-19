import classes from './LandingPage.module.css';

export interface LandingPageProps {
  signedIn: boolean;
  sessionLoading: boolean;
}

const features = [
  {
    title: 'Compare players in conversation',
    description:
      'Ask about players side by side and get an answer grounded in stored statistics and useful context.',
  },
  {
    title: 'Plan your next move',
    description:
      'Explore fixtures, captain options and transfer ideas without turning your planning into a spreadsheet.',
  },
  {
    title: 'Bring your team into it',
    description:
      'Connect your FPL team to discuss your squad, see mini-league opponents and make the advice more relevant.',
  },
  {
    title: 'Keep a shortlist',
    description:
      'Save interesting players as you research, then return to the names worth considering before the deadline.',
  },
] as const;

export default function LandingPage({ signedIn, sessionLoading }: Readonly<LandingPageProps>) {
  return (
    <div className={classes.page} aria-busy={sessionLoading}>
      <header className={classes.header}>
        <a className={classes.brand} href="/" aria-label="FPL Sidekick home">
          FPL Sidekick
        </a>
        {signedIn ? (
          <a className={classes.headerLink} href="/app">
            Open the app
          </a>
        ) : (
          <nav className={classes.accountLinks} aria-label="Account">
            <a className={classes.headerLink} href="/login">
              Log in
            </a>
            <a className={classes.headerRegister} href="/register">
              Register
            </a>
          </nav>
        )}
      </header>

      <main>
        <section className={classes.hero} aria-labelledby="landing-title">
          <div className={classes.heroCopy}>
            <strong className={classes.badge}>Now free</strong>
            <h1 id="landing-title" className={classes.title}>
              Your FPL decisions, backed by data.
            </h1>
            <p className={classes.lede}>
              FPL Sidekick is a chat-based planning companion for exploring players, fixtures and
              the choices facing your team.
            </p>
            <div className={classes.actions}>
              {signedIn ? (
                <a className={classes.primaryAction} href="/app">
                  Open FPL Sidekick
                </a>
              ) : (
                <>
                  <a className={classes.primaryAction} href="/register">
                    Register free
                  </a>
                  <a className={classes.secondaryAction} href="/login">
                    Log in
                  </a>
                </>
              )}
            </div>
            {!signedIn && (
              <p className={classes.inviteNote}>Free during testing · Invite code required</p>
            )}
          </div>

          <aside className={classes.example} aria-label="Example questions">
            <p className={classes.exampleLabel}>Ask your sidekick</p>
            <ul>
              <li>Who suits my midfield spot?</li>
              <li>What should I consider before captaining this player?</li>
              <li>How does my squad look for the next few fixtures?</li>
            </ul>
            <p className={classes.exampleNote}>
              Evidence for a decision, not a promise about the result.
            </p>
          </aside>
        </section>

        <section className={classes.features} aria-labelledby="features-title">
          <div className={classes.sectionHeading}>
            <p className={classes.kicker}>One place to think it through</p>
            <h2 id="features-title">Useful before every deadline</h2>
          </div>
          <div className={classes.featureGrid}>
            {features.map((feature) => (
              <article className={classes.featureCard} key={feature.title}>
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
              </article>
            ))}
          </div>
        </section>
      </main>

      <footer className={classes.footer}>
        <span>FPL Sidekick</span>
        <a href="/privacy">Privacy</a>
        <span>Not affiliated with or endorsed by the Premier League.</span>
      </footer>
    </div>
  );
}
