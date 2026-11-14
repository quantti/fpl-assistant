# FPL Assistant

A Fantasy Premier League chat application. Spring Boot serves a React/Mantine frontend,
with Supabase authentication and PostgreSQL-backed statistics and strategy retrieval.
Chat providers are configurable; OpenAI embeddings support strategy search.

## Local development

Requires Java 25, Node.js 24 and Docker for the disposable PostgreSQL test database.

- Copy `backend/.env.test.example` and `frontend/.env.example` to local ignored env files.
- Supply your own provider credentials and Supabase project configuration. Example files contain
  no working credentials. Never run tests against a shared or production database.
- Start the disposable test database with `scripts/test-db.sh up`.
- In `backend/`, load the test env and run `./gradlew bootRun` or focused `./gradlew test --tests '…'`.
- In `frontend/`, run `npm ci` and `npm run dev`.

Frontend verification: `npm test`, `npm run lint`, `npm run format:check`, `npm run build`.
Run one build/test process at a time. Production and account/email operations need separate
operator approval; local mocked tests do not establish production delivery or data freshness.

## Deployment configuration

Set backend `SUPABASE_URL` and frontend `VITE_SUPABASE_URL` to your own Supabase project.
Neither has a production fallback. Docker builds accept `VITE_SUPABASE_URL` as a build argument;
the retained deployment workflow reads the GitHub secret of the same name. Configure both
before starting the application or re-enabling deployment. Tests use a loopback-only URL.
For schema exports, set `SHARED_DB_HOST` to your own session-pooler host; the export script
never selects a production host by default.

Production frontend builds require `VITE_PRIVACY_CONTROLLER_NAME` and
`VITE_PRIVACY_CONTACT_EMAIL`. Supply the real controller and working mailbox through deployment
configuration, not source control. These values are intentionally public in the deployed bundle
and privacy notice. They are not credentials. CI verification uses synthetic example values.

The deploy workflow reads these values from GitHub repository secrets of the same names and
passes them as Docker build arguments. **Configure both before merging/deploying this change.**
Missing or invalid contact configuration blocks the build rather than publishing a privacy notice
without contact details. Configure `DAILY_PRICE_ALERT_EMAIL_TO` separately in runtime settings if
price-alert mail is enabled; its checked-in default is empty and mail is disabled by default.
No production configuration is changed by adding this source code.

Account-email templates under `docs/email-templates/` link to the deployed privacy notice for
contact details. Apply templates through Supabase only with operator approval; source changes do
not update the live dashboard automatically.

## Publishing and operations

Internal incident reports, account evidence, agent instructions and operational plans are not
part of the public distribution. Maintain deployment records and private backups separately.
GitHub Actions is currently disabled, including automatic deployment. Workflow definitions are
retained but do not run until an operator explicitly re-enables Actions. Run the local checks
above before publication; never push directly to `master`.

A repository visibility change does not erase old Git objects, PR comments, Actions artifacts,
clones or caches. Before making a repository public, finish the separately controlled history
replacement and GitHub-side cleanup; a clean current tree is not sufficient.

## License

Project-authored code is available under the [MIT License](LICENSE). Third-party dependencies,
fonts and external football data retain their own licenses and terms; this license grants no
additional rights to those materials or permission to access external services.
