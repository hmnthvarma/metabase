import { t } from "ttag";

import { useSelector } from "metabase/lib/redux";
import { getNoDataIllustration } from "metabase/selectors/whitelabel";
import type { ImageProps } from "metabase/ui";
import { Image } from "metabase/ui";

export function NoDataError(props: ImageProps) {
  const noDataIllustration = useSelector(getNoDataIllustration);

  return noDataIllustration ? (
    <Image
      alt={t`No results`}
      w={120}
      h={120}
      src={noDataIllustration}
      {...props}
    />
  ) : null;
}
