import type * as React from "react";
import { useRef, useState } from "react";
import { jt, t } from "ttag";

import { updateSettings } from "metabase/admin/settings/settings";
import {
  skipToken,
  useListDatabasesQuery,
  useListSyncableDatabaseSchemasQuery,
} from "metabase/api";
import { useSetting } from "metabase/common/hooks";
import ActionButton from "metabase/components/ActionButton";
import EmptyState from "metabase/components/EmptyState/EmptyState";
import { LoadingAndErrorWrapper } from "metabase/components/LoadingAndErrorWrapper";
import Alert from "metabase/core/components/Alert";
import Input from "metabase/core/components/Input";
import Link from "metabase/core/components/Link";
import type { SelectChangeEvent } from "metabase/core/components/Select";
import Select from "metabase/core/components/Select";
import CS from "metabase/css/core/index.css";
import Databases from "metabase/entities/databases";
import { useDispatch, useSelector } from "metabase/lib/redux";
import { getIsHosted } from "metabase/setup/selectors";
import { Group, Stack, Text, Tooltip } from "metabase/ui";
import type { Database } from "metabase-types/api";
import type { UploadsSettings } from "metabase-types/api/settings";

import { SettingHeader } from "../SettingHeader";

import { ColorText, PaddedForm, SectionTitle } from "./UploadSetting.styled";
import { dbHasSchema, getDatabaseOptions, getSchemaOptions } from "./utils";

const FEEDBACK_TIMEOUT = 5000;
const enableErrorMessage = t`There was a problem enabling uploads. Please try again shortly.`;
const disableErrorMessage = t`There was a problem disabling uploads. Please try again shortly.`;

export type SaveStatusRef = React.RefObject<{
  setSaving: () => void;
  setSaved: () => void;
  setSaveError: (msg: string) => void;
  clear: () => void;
}>;

interface UploadSettingProps {
  databases: Database[];
  uploadsSettings: UploadsSettings;
  updateSettings: (
    settings: Record<
      string,
      string | number | boolean | UploadsSettings | null
    >,
  ) => Promise<void>;
  saveStatusRef: SaveStatusRef;
}

const Header = () => (
  <SettingHeader
    id="upload-settings"
    title={t`Allow people to upload data to Collections`}
    description={jt`People will be able to upload CSV files that will be stored in the ${(
      <Link
        className={CS.link}
        key="db-link"
        to="/admin/databases"
      >{t`database`}</Link>
    )} you choose and turned into models.`}
  />
);

export function UploadSettingsFormView({
  databases,
  uploadsSettings,
  updateSettings,
  saveStatusRef,
}: UploadSettingProps) {
  const [dbId, setDbId] = useState<number | null>(
    uploadsSettings.db_id ?? null,
  );
  const [schemaName, setSchemaName] = useState<string | null>(
    uploadsSettings.schema_name ?? null,
  );
  const [tablePrefix, setTablePrefix] = useState<string | null>(
    uploadsSettings.table_prefix ?? null,
  );
  const [errorMessage, setErrorMessage] = useState<null | string>(null);
  const dispatch = useDispatch();

  const showSchema = Boolean(dbId && dbHasSchema(databases, dbId));
  const databaseOptions = getDatabaseOptions(databases);

  const isHosted = useSelector(getIsHosted);

  const enableButtonRef = useRef<ActionButton>(null);
  const disableButtonRef = useRef<ActionButton>(null);
  const updateButtonRef = useRef<ActionButton>(null);

  const resetButtons = () => {
    enableButtonRef?.current?.resetState();
    disableButtonRef?.current?.resetState();
    updateButtonRef?.current?.resetState();
  };

  const showError = (msg: string) => {
    setErrorMessage(msg);
    setTimeout(() => setErrorMessage(null), FEEDBACK_TIMEOUT);
    saveStatusRef?.current?.clear();
  };

  const showSaving = () => {
    saveStatusRef?.current?.setSaving();
  };

  const handleEnableUploads = async () => {
    showSaving();
    return updateSettings({
      "uploads-settings": {
        db_id: dbId,
        schema_name: schemaName,
        table_prefix: tablePrefix,
      },
    })
      .then(() => {
        setSchemaName(schemaName);
        setTablePrefix(tablePrefix);
        saveStatusRef?.current?.setSaved();
        dispatch(Databases.actions.invalidateLists());
      })
      .catch(() => showError(enableErrorMessage));
  };

  const handleDisableUploads = () => {
    showSaving();
    return updateSettings({
      "uploads-settings": {
        db_id: null,
        schema_name: null,
        table_prefix: null,
      },
    })
      .then(() => {
        setDbId(null);
        setSchemaName(null);
        setTablePrefix(null);
        saveStatusRef?.current?.setSaved();
      })
      .catch(() => showError(disableErrorMessage));
  };

  const showPrefix = !!dbId;
  const hasValidSettings = Boolean(dbId && (!showSchema || schemaName));
  const settingsChanged =
    dbId !== uploadsSettings.db_id ||
    schemaName !== uploadsSettings.schema_name ||
    tablePrefix !== uploadsSettings.table_prefix;

  const hasValidDatabases = databaseOptions.length > 0;
  const isH2db = Boolean(
    dbId && databases.find((db) => db.id === dbId)?.engine === "h2",
  );

  const {
    data: schemas,
    error: schemasError,
    isFetching: schemasIsFetching,
  } = useListSyncableDatabaseSchemasQuery(
    showSchema && dbId != null ? dbId : skipToken,
  );

  return (
    <PaddedForm aria-label={t`Upload Settings Form`}>
      <Header />
      {isH2db && <H2PersistenceWarning isHosted={isHosted} />}
      <Group>
        <Stack>
          <SectionTitle>{t`Database to use for uploads`}</SectionTitle>
          <Select
            value={dbId ?? 0}
            placeholder={t`Select a database`}
            disabled={!hasValidDatabases}
            options={databaseOptions}
            onChange={(e: SelectChangeEvent<number>) => {
              setDbId(e.target.value);
              if (e.target.value) {
                resetButtons();
                dbHasSchema(databases, e.target.value)
                  ? setTablePrefix(null)
                  : setTablePrefix("upload_");
                setSchemaName(null);
              }
            }}
          />
        </Stack>

        {showSchema && (schemasError || schemasIsFetching) && (
          <LoadingAndErrorWrapper
            error={schemasError}
            loading={schemasIsFetching}
          />
        )}

        {showSchema && !schemasError && !schemasIsFetching && (
          <Stack>
            <SectionTitle>{t`Schema`}</SectionTitle>
            {schemas?.length ? (
              <Select
                value={schemaName ?? ""}
                placeholder={t`Select a schema`}
                options={getSchemaOptions(schemas)}
                onChange={(e: SelectChangeEvent<string>) => {
                  resetButtons();
                  setSchemaName(e.target.value);
                }}
              />
            ) : (
              <EmptyState message={t`We couldn't find any schema.`} />
            )}
          </Stack>
        )}

        {showPrefix && (
          <Stack>
            <SectionTitle>{t`Upload Table Prefix (optional)`}</SectionTitle>
            <Input
              value={tablePrefix ?? ""}
              placeholder={t`upload_`}
              onChange={(e) => {
                resetButtons();
                setTablePrefix(e.target.value);
              }}
            />
          </Stack>
        )}
      </Group>
      <Group mt="lg">
        {uploadsSettings.db_id ? (
          settingsChanged ? (
            <ActionButton
              ref={updateButtonRef}
              normalText={t`Update settings`}
              successText={t`Settings updated`}
              disabled={!hasValidSettings}
              failedText={t`Failed to save upload settings`}
              actionFn={handleEnableUploads}
              primary
              useLoadingSpinner
              type="submit"
            />
          ) : (
            <ActionButton
              ref={disableButtonRef}
              normalText={t`Disable uploads`}
              successText={
                t`Uploads enabled` /* yes, this is backwards intentionally */
              }
              failedText={t`Failed to disable uploads`}
              actionFn={handleDisableUploads}
              type="button"
              danger
              useLoadingSpinner
            />
          )
        ) : (
          <ActionButton
            ref={enableButtonRef}
            normalText={t`Enable uploads`}
            successText={
              t`Uploads disabled` /* yes, this is backwards intentionally */
            }
            failedText={t`Failed to enable uploads`}
            actionFn={handleEnableUploads}
            primary={!!hasValidSettings}
            disabled={!hasValidSettings || !hasValidDatabases}
            useLoadingSpinner
            type="submit"
          />
        )}
      </Group>
      {!hasValidDatabases && <NoValidDatabasesMessage />}
      {errorMessage && <ColorText color="danger">{errorMessage}</ColorText>}
    </PaddedForm>
  );
}

const H2PersistenceWarning = ({ isHosted }: { isHosted: boolean }) => (
  <Stack my="md" maw={620}>
    <Alert icon="warning" variant="warning">
      <Text>
        {t`Warning: uploads to the Sample Database are for testing only and may disappear. If you want your data to stick around, you should upload to a PostgreSQL or MySQL database.`}
      </Text>
      {isHosted && (
        <Tooltip
          label={
            <>
              <Text mb="md">{t`By enabling uploads to the Sample Database, you agree that you will not upload or otherwise transmit any individually identifiable information, including without limitation Personal Data (as defined by the General Data Protection Regulation) or Personally Identifiable Information (as defined by the California Consumer Privacy Act and California Privacy Rights Act).`}</Text>
              <Text>{t`Additionally, you acknowledge and agree that the ability to upload to the Sample Database is provided “as is” and without warranty of any kind, and Metabase disclaims all warranties, express or implied, and all liability in connection with the uploads to the Sample Database or the data stored within it.`}</Text>
            </>
          }
          position="bottom"
          multiline
          maw="30rem"
        >
          <Text
            component="span"
            td="underline"
            fw={700}
          >{t`Additional terms apply.`}</Text>
        </Tooltip>
      )}
    </Alert>
  </Stack>
);

const NoValidDatabasesMessage = () => (
  <>
    <p>
      {t`None of your databases are compatible with this version of the uploads feature.`}
    </p>
    <p>
      {jt`Metabase currently supports ${(
        <strong key="db-types">{t`Postgres, MySQL, and H2`}</strong>
      )} for uploads and needs a connection with write privileges.`}
    </p>
  </>
);

export const UploadSettingsForm = ({
  saveStatusRef,
}: {
  saveStatusRef: SaveStatusRef;
}) => {
  const dispatch = useDispatch();
  const { data, isLoading, error } = useListDatabasesQuery({
    include_only_uploadable: true,
  });

  const databases = data?.data ?? [];
  const uploadsSettings = useSetting("uploads-settings");

  return (
    <LoadingAndErrorWrapper loading={isLoading} error={error} noWrapper>
      <UploadSettingsFormView
        databases={databases}
        uploadsSettings={uploadsSettings}
        updateSettings={async (settings) => {
          dispatch(updateSettings(settings));
        }}
        saveStatusRef={saveStatusRef}
      />
    </LoadingAndErrorWrapper>
  );
};
