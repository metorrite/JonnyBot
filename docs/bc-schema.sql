-- The BotCommands library's own tables (schema "bc", BotCommands 3.1.0), dumped schema-only from the local dev database on 2026-10-14.
-- BotCommands creates these itself on a first start; this file is only a fallback for a fresh database that complains they are missing:
--     psql -U postgres -d jonnybot_dev -f docs/bc-schema.sql
-- (an error saying schema "bc" already exists means they are already there: nothing to do).
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: bc; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA bc;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: application_commands_cache; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.application_commands_cache (
    application_id bigint NOT NULL,
    guild_id bigint,
    data text NOT NULL,
    metadata text NOT NULL
);


--
-- Name: bc_component; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_component (
    component_id integer NOT NULL,
    component_type smallint NOT NULL,
    lifetime_type smallint NOT NULL,
    one_use boolean NOT NULL,
    rate_limit_group text,
    filters text[] NOT NULL,
    expires_at timestamp with time zone,
    reset_timeout_on_use_duration_ms integer,
    rate_limit_discriminator text,
    CONSTRAINT bc_component_component_type_check CHECK (((component_type >= 0) AND (component_type <= 2))),
    CONSTRAINT bc_component_lifetime_type_check CHECK (((lifetime_type >= 0) AND (lifetime_type <= 1)))
);


--
-- Name: bc_component_component_group; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_component_component_group (
    group_id integer NOT NULL,
    component_id integer NOT NULL
);


--
-- Name: bc_component_component_id_seq; Type: SEQUENCE; Schema: bc; Owner: -
--

CREATE SEQUENCE bc.bc_component_component_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: bc_component_component_id_seq; Type: SEQUENCE OWNED BY; Schema: bc; Owner: -
--

ALTER SEQUENCE bc.bc_component_component_id_seq OWNED BY bc.bc_component.component_id;


--
-- Name: bc_component_constraints; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_component_constraints (
    component_id integer NOT NULL,
    users bigint[] NOT NULL,
    roles bigint[] NOT NULL,
    permissions bigint NOT NULL
);


--
-- Name: bc_ephemeral_handler; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_ephemeral_handler (
    component_id integer NOT NULL,
    handler_id integer NOT NULL
);


--
-- Name: bc_ephemeral_timeout; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_ephemeral_timeout (
    component_id integer NOT NULL,
    handler_id integer NOT NULL
);


--
-- Name: bc_persistent_handler; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_persistent_handler (
    component_id integer NOT NULL,
    handler_name text NOT NULL,
    user_data bytea[] NOT NULL
);


--
-- Name: bc_persistent_timeout; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_persistent_timeout (
    component_id integer NOT NULL,
    handler_name text NOT NULL,
    user_data bytea[] NOT NULL
);


--
-- Name: bc_version; Type: TABLE; Schema: bc; Owner: -
--

CREATE TABLE bc.bc_version (
    one_row boolean DEFAULT true NOT NULL,
    version text NOT NULL,
    CONSTRAINT bc_version_one_row_check CHECK (one_row)
);


--
-- Name: bc_component component_id; Type: DEFAULT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component ALTER COLUMN component_id SET DEFAULT nextval('bc.bc_component_component_id_seq'::regclass);


--
-- Name: application_commands_cache application_commands_cache_application_id_guild_id_key; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.application_commands_cache
    ADD CONSTRAINT application_commands_cache_application_id_guild_id_key UNIQUE NULLS NOT DISTINCT (application_id, guild_id);


--
-- Name: bc_component_component_group bc_component_component_group_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component_component_group
    ADD CONSTRAINT bc_component_component_group_pkey PRIMARY KEY (group_id, component_id);


--
-- Name: bc_component_constraints bc_component_constraints_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component_constraints
    ADD CONSTRAINT bc_component_constraints_pkey PRIMARY KEY (component_id);


--
-- Name: bc_component bc_component_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component
    ADD CONSTRAINT bc_component_pkey PRIMARY KEY (component_id);


--
-- Name: bc_ephemeral_handler bc_ephemeral_handler_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_ephemeral_handler
    ADD CONSTRAINT bc_ephemeral_handler_pkey PRIMARY KEY (component_id);


--
-- Name: bc_ephemeral_timeout bc_ephemeral_timeout_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_ephemeral_timeout
    ADD CONSTRAINT bc_ephemeral_timeout_pkey PRIMARY KEY (component_id);


--
-- Name: bc_persistent_handler bc_persistent_handler_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_persistent_handler
    ADD CONSTRAINT bc_persistent_handler_pkey PRIMARY KEY (component_id);


--
-- Name: bc_persistent_timeout bc_persistent_timeout_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_persistent_timeout
    ADD CONSTRAINT bc_persistent_timeout_pkey PRIMARY KEY (component_id);


--
-- Name: bc_version bc_version_pkey; Type: CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_version
    ADD CONSTRAINT bc_version_pkey PRIMARY KEY (one_row);


--
-- Name: bc_component_component_group bc_component_component_group_component_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component_component_group
    ADD CONSTRAINT bc_component_component_group_component_id_fkey FOREIGN KEY (component_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- Name: bc_component_component_group bc_component_component_group_group_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component_component_group
    ADD CONSTRAINT bc_component_component_group_group_id_fkey FOREIGN KEY (group_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- Name: bc_component_constraints bc_component_constraints_component_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_component_constraints
    ADD CONSTRAINT bc_component_constraints_component_id_fkey FOREIGN KEY (component_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- Name: bc_ephemeral_handler bc_ephemeral_handler_component_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_ephemeral_handler
    ADD CONSTRAINT bc_ephemeral_handler_component_id_fkey FOREIGN KEY (component_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- Name: bc_ephemeral_timeout bc_ephemeral_timeout_component_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_ephemeral_timeout
    ADD CONSTRAINT bc_ephemeral_timeout_component_id_fkey FOREIGN KEY (component_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- Name: bc_persistent_handler bc_persistent_handler_component_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_persistent_handler
    ADD CONSTRAINT bc_persistent_handler_component_id_fkey FOREIGN KEY (component_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- Name: bc_persistent_timeout bc_persistent_timeout_component_id_fkey; Type: FK CONSTRAINT; Schema: bc; Owner: -
--

ALTER TABLE ONLY bc.bc_persistent_timeout
    ADD CONSTRAINT bc_persistent_timeout_component_id_fkey FOREIGN KEY (component_id) REFERENCES bc.bc_component(component_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--



--
-- The version row BotCommands checks at startup (the schema-only dump above left it out; with it a fresh database starts).
--

INSERT INTO bc.bc_version (one_row, version) VALUES (true, '3.0.0') ON CONFLICT DO NOTHING;
