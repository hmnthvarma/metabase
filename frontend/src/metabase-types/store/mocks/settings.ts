import type { EnterpriseSettings, Settings } from "metabase-types/api";
import { createMockSettings } from "metabase-types/api/mocks";
import type { SettingsState } from "metabase-types/store";

export const createMockSettingsState = (
  opts?: Partial<Settings> | Partial<EnterpriseSettings>,
): SettingsState => ({
  values: createMockSettings(opts),
  loading: false,
});
