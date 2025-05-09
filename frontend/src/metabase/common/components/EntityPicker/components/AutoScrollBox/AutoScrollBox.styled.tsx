// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";
import type React from "react";

import type { BoxProps } from "metabase/ui";
import { Box } from "metabase/ui";

export const HorizontalScrollBox = styled(Box)<
  BoxProps & React.HTMLProps<HTMLDivElement>
>`
  overflow-x: auto;
` as unknown as typeof Box;
