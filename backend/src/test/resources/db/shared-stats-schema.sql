--
-- GENERATED FILE — DO NOT HAND-EDIT.
--
-- Source: upstream-stats-app's tables in the shared Supabase database.
-- Regenerate: scripts/dump-test-schema.sh   (verify: scripts/dump-test-schema.sh --check)
-- Re-run after any shared migration; a stale copy means CI passes while production SQL breaks.
--
-- Contains ONLY the six tables this project reads and does not own:
--   season team player gameweek fixture player_fixture_stats
-- This project's own tables are created by its @PostConstruct startup DDL and must never be
-- added here. The pgvector extension is created separately by the CI job and the local
-- setup documented in docs/DEVELOPMENT.md.
--
-- Schema only: no rows. Tests seed the data they assert on.
--

CREATE OR REPLACE FUNCTION public.update_pfs_updated_at()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$function$
;
--
-- PostgreSQL database dump
--



SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_table_access_method = heap;

--
-- Name: fixture; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fixture (
    id integer NOT NULL,
    season_id integer NOT NULL,
    gameweek integer,
    code integer NOT NULL,
    team_h integer NOT NULL,
    team_a integer NOT NULL,
    team_h_score integer,
    team_a_score integer,
    team_h_difficulty integer,
    team_a_difficulty integer,
    kickoff_time timestamp with time zone,
    started boolean DEFAULT false,
    finished boolean DEFAULT false,
    finished_provisional boolean DEFAULT false,
    minutes integer DEFAULT 0,
    stats jsonb DEFAULT '[]'::jsonb,
    updated_at timestamp with time zone DEFAULT now()
);


--
-- Name: gameweek; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.gameweek (
    id integer NOT NULL,
    season_id integer NOT NULL,
    name character varying(50),
    deadline_time timestamp with time zone NOT NULL,
    finished boolean DEFAULT false,
    data_checked boolean DEFAULT false,
    is_current boolean DEFAULT false,
    is_next boolean DEFAULT false,
    average_entry_score integer,
    highest_score integer,
    transfers_made integer,
    most_selected integer,
    most_transferred_in integer,
    most_captained integer,
    most_vice_captained integer,
    top_element integer,
    updated_at timestamp with time zone DEFAULT now()
);


--
-- Name: player; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.player (
    id integer NOT NULL,
    season_id integer NOT NULL,
    team_id integer NOT NULL,
    first_name character varying(100),
    second_name character varying(100),
    web_name character varying(100) NOT NULL,
    element_type integer NOT NULL,
    now_cost integer NOT NULL,
    status character varying(1) DEFAULT 'a'::character varying,
    news text,
    news_added timestamp with time zone,
    total_points integer DEFAULT 0,
    minutes integer DEFAULT 0,
    goals_scored integer DEFAULT 0,
    assists integer DEFAULT 0,
    clean_sheets integer DEFAULT 0,
    goals_conceded integer DEFAULT 0,
    own_goals integer DEFAULT 0,
    penalties_saved integer DEFAULT 0,
    penalties_missed integer DEFAULT 0,
    yellow_cards integer DEFAULT 0,
    red_cards integer DEFAULT 0,
    saves integer DEFAULT 0,
    bonus integer DEFAULT 0,
    bps integer DEFAULT 0,
    expected_goals numeric(6,2) DEFAULT 0,
    expected_assists numeric(6,2) DEFAULT 0,
    expected_goal_involvements numeric(6,2) DEFAULT 0,
    expected_goals_conceded numeric(6,2) DEFAULT 0,
    influence numeric(6,1) DEFAULT 0,
    creativity numeric(6,1) DEFAULT 0,
    threat numeric(6,1) DEFAULT 0,
    ict_index numeric(6,1) DEFAULT 0,
    selected_by_percent numeric(5,2) DEFAULT 0,
    form numeric(4,1) DEFAULT 0,
    points_per_game numeric(4,1) DEFAULT 0,
    updated_at timestamp with time zone DEFAULT now()
);


--
-- Name: player_fixture_stats; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.player_fixture_stats (
    fixture_id integer NOT NULL,
    player_id integer NOT NULL,
    season_id integer NOT NULL,
    gameweek smallint NOT NULL,
    player_team_id integer NOT NULL,
    opponent_team_id integer NOT NULL,
    was_home boolean NOT NULL,
    kickoff_time timestamp with time zone,
    minutes smallint DEFAULT 0 NOT NULL,
    total_points smallint DEFAULT 0 NOT NULL,
    bonus smallint DEFAULT 0 NOT NULL,
    bps smallint DEFAULT 0 NOT NULL,
    goals_scored smallint DEFAULT 0 NOT NULL,
    assists smallint DEFAULT 0 NOT NULL,
    expected_goals numeric(5,2) DEFAULT 0 NOT NULL,
    expected_assists numeric(5,2) DEFAULT 0 NOT NULL,
    expected_goal_involvements numeric(5,2) DEFAULT 0 NOT NULL,
    clean_sheets smallint DEFAULT 0 NOT NULL,
    goals_conceded smallint DEFAULT 0 NOT NULL,
    own_goals smallint DEFAULT 0 NOT NULL,
    penalties_saved smallint DEFAULT 0 NOT NULL,
    penalties_missed smallint DEFAULT 0 NOT NULL,
    saves smallint DEFAULT 0 NOT NULL,
    expected_goals_conceded numeric(5,2) DEFAULT 0 NOT NULL,
    yellow_cards smallint DEFAULT 0 NOT NULL,
    red_cards smallint DEFAULT 0 NOT NULL,
    influence numeric(6,1) DEFAULT 0 NOT NULL,
    creativity numeric(6,1) DEFAULT 0 NOT NULL,
    threat numeric(6,1) DEFAULT 0 NOT NULL,
    ict_index numeric(6,1) DEFAULT 0 NOT NULL,
    value integer NOT NULL,
    selected integer DEFAULT 0 NOT NULL,
    transfers_in integer DEFAULT 0 NOT NULL,
    transfers_out integer DEFAULT 0 NOT NULL,
    starts smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    CONSTRAINT chk_pfs_selected_non_negative CHECK ((selected >= 0))
);


--
-- Name: TABLE player_fixture_stats; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.player_fixture_stats IS 'Per-player per-fixture stats from FPL API element-summary endpoint. Used for recommendations engine.';


--
-- Name: COLUMN player_fixture_stats.bps; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.player_fixture_stats.bps IS 'Bonus Points System raw score (determines bonus allocation)';


--
-- Name: COLUMN player_fixture_stats.value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.player_fixture_stats.value IS 'Player price at time of match, multiplied by 10 (e.g., 100 = £10.0m)';


--
-- Name: COLUMN player_fixture_stats.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.player_fixture_stats.updated_at IS 'Timestamp of last update, auto-maintained by trigger';


--
-- Name: season; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.season (
    id integer NOT NULL,
    code character varying(10) NOT NULL,
    name character varying(50) NOT NULL,
    start_date date NOT NULL,
    end_date date,
    is_current boolean DEFAULT false,
    created_at timestamp with time zone DEFAULT now()
);


--
-- Name: team; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.team (
    id integer NOT NULL,
    season_id integer NOT NULL,
    code integer NOT NULL,
    name character varying(100) NOT NULL,
    short_name character varying(3) NOT NULL,
    strength integer,
    strength_overall_home integer,
    strength_overall_away integer,
    strength_attack_home integer,
    strength_attack_away integer,
    strength_defence_home integer,
    strength_defence_away integer,
    updated_at timestamp with time zone DEFAULT now()
);


--
-- Name: season_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.season_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: season_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.season_id_seq OWNED BY public.season.id;


--
-- Name: season id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.season ALTER COLUMN id SET DEFAULT nextval('public.season_id_seq'::regclass);


--
-- Name: fixture fixture_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fixture
    ADD CONSTRAINT fixture_pkey PRIMARY KEY (id, season_id);


--
-- Name: gameweek gameweek_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gameweek
    ADD CONSTRAINT gameweek_pkey PRIMARY KEY (id, season_id);


--
-- Name: player_fixture_stats player_fixture_stats_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_fixture_stats
    ADD CONSTRAINT player_fixture_stats_pkey PRIMARY KEY (fixture_id, player_id, season_id);


--
-- Name: player player_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player
    ADD CONSTRAINT player_pkey PRIMARY KEY (id, season_id);


--
-- Name: season season_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.season
    ADD CONSTRAINT season_code_key UNIQUE (code);


--
-- Name: season season_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.season
    ADD CONSTRAINT season_pkey PRIMARY KEY (id);


--
-- Name: team team_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team
    ADD CONSTRAINT team_pkey PRIMARY KEY (id, season_id);


--
-- Name: idx_fixture_gameweek; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fixture_gameweek ON public.fixture USING btree (season_id, gameweek);


--
-- Name: idx_fixture_kickoff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fixture_kickoff ON public.fixture USING btree (kickoff_time);


--
-- Name: idx_fixture_season; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fixture_season ON public.fixture USING btree (season_id);


--
-- Name: idx_fixture_teams; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fixture_teams ON public.fixture USING btree (team_h, team_a);


--
-- Name: idx_gameweek_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gameweek_current ON public.gameweek USING btree (season_id, is_current) WHERE (is_current = true);


--
-- Name: idx_pfs_form_queries; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_form_queries ON public.player_fixture_stats USING btree (player_id, season_id, gameweek DESC) WHERE (minutes > 0);


--
-- Name: idx_pfs_opponent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_opponent ON public.player_fixture_stats USING btree (opponent_team_id, season_id);


--
-- Name: idx_pfs_player_gw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_player_gw ON public.player_fixture_stats USING btree (player_id, season_id, gameweek DESC);


--
-- Name: idx_pfs_player_gw_asc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_player_gw_asc ON public.player_fixture_stats USING btree (player_id, season_id, gameweek);


--
-- Name: idx_pfs_player_season; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_player_season ON public.player_fixture_stats USING btree (player_id, season_id);


--
-- Name: idx_pfs_season_gw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_season_gw ON public.player_fixture_stats USING btree (season_id, gameweek);


--
-- Name: idx_pfs_xg_delta; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pfs_xg_delta ON public.player_fixture_stats USING btree (season_id, (((goals_scored)::numeric - expected_goals)) DESC) WHERE (minutes >= 60);


--
-- Name: idx_player_element_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_player_element_type ON public.player USING btree (element_type, season_id);


--
-- Name: idx_player_ownership; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_player_ownership ON public.player USING btree (season_id, selected_by_percent DESC);


--
-- Name: idx_player_team; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_player_team ON public.player USING btree (team_id, season_id);


--
-- Name: idx_season_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_season_current ON public.season USING btree (is_current) WHERE (is_current = true);


--
-- Name: player_fixture_stats trg_pfs_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_pfs_updated_at BEFORE UPDATE ON public.player_fixture_stats FOR EACH ROW EXECUTE FUNCTION public.update_pfs_updated_at();


--
-- Name: fixture fixture_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fixture
    ADD CONSTRAINT fixture_season_id_fkey FOREIGN KEY (season_id) REFERENCES public.season(id);


--
-- Name: fixture fixture_team_a_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fixture
    ADD CONSTRAINT fixture_team_a_season_id_fkey FOREIGN KEY (team_a, season_id) REFERENCES public.team(id, season_id);


--
-- Name: fixture fixture_team_h_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fixture
    ADD CONSTRAINT fixture_team_h_season_id_fkey FOREIGN KEY (team_h, season_id) REFERENCES public.team(id, season_id);


--
-- Name: gameweek gameweek_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gameweek
    ADD CONSTRAINT gameweek_season_id_fkey FOREIGN KEY (season_id) REFERENCES public.season(id);


--
-- Name: player_fixture_stats player_fixture_stats_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_fixture_stats
    ADD CONSTRAINT player_fixture_stats_season_id_fkey FOREIGN KEY (season_id) REFERENCES public.season(id);


--
-- Name: player player_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player
    ADD CONSTRAINT player_season_id_fkey FOREIGN KEY (season_id) REFERENCES public.season(id);


--
-- Name: player player_team_id_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player
    ADD CONSTRAINT player_team_id_season_id_fkey FOREIGN KEY (team_id, season_id) REFERENCES public.team(id, season_id);


--
-- Name: team team_season_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team
    ADD CONSTRAINT team_season_id_fkey FOREIGN KEY (season_id) REFERENCES public.season(id);


--
-- PostgreSQL database dump complete
--


