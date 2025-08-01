import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import {
  setupCollectionByIdEndpoint,
  setupDashboardCreateEndpoint,
} from "__support__/server-mocks";
import { screen, waitFor } from "__support__/ui";
import { getNextId } from "__support__/utils";
import { renderWithSDKProviders } from "embedding-sdk/test/__support__/ui";
import { createMockSdkConfig } from "embedding-sdk/test/mocks/config";
import { useLocale } from "metabase/common/hooks/use-locale";
import { ROOT_COLLECTION as ROOT } from "metabase/entities/collections";
import {
  createMockCollection,
  createMockDashboard,
  createMockUser,
} from "metabase-types/api/mocks";

import {
  CreateDashboardModal,
  type CreateDashboardModalProps,
} from "./CreateDashboardModal";

jest.mock("metabase/common/hooks/use-locale", () => ({
  useLocale: jest.fn(),
}));

const useLocaleMock = useLocale as jest.Mock;

const CURRENT_USER = createMockUser({
  id: getNextId(),
  personal_collection_id: getNextId(),
  is_superuser: true,
});

const ROOT_COLLECTION = createMockCollection({
  ...ROOT,
  can_write: true,
});

const PERSONAL_COLLECTION = createMockCollection({
  id: CURRENT_USER.personal_collection_id,
  name: "Personal collection",
  can_write: true,
  is_personal: true,
  location: "/",
});

const COLLECTIONS = [ROOT_COLLECTION, PERSONAL_COLLECTION];

describe("CreateDashboardModal", () => {
  it("should render a loader when a locale is loading", async () => {
    setup({ isLocaleLoading: true });

    expect(screen.queryByText("New dashboard")).not.toBeInTheDocument();
  });

  it("should render", async () => {
    setup();

    expect(screen.getByText("New dashboard")).toBeInTheDocument();

    expect(screen.getByText("Description")).toBeInTheDocument();

    expect(
      screen.getByText("Which collection should this go in?"),
    ).toBeInTheDocument();
  });

  it("should create a new dashboard on form submit", async () => {
    const mockResponseDashboard = createMockDashboard({
      name: "My awesome dashboard title",
    });
    setupDashboardCreateEndpoint(mockResponseDashboard);

    const onCreate = jest.fn();

    setup({
      props: {
        onCreate,
      },
    });

    await userEvent.type(
      screen.getByPlaceholderText("What is the name of your dashboard?"),
      "My awesome dashboard title",
    );

    await waitFor(() => {
      return expect(
        fetchMock.called(`path:/api/collection/${PERSONAL_COLLECTION.id}`),
      ).toBe(true);
    });

    expect(screen.getByTestId("collection-picker-button")).toHaveTextContent(
      PERSONAL_COLLECTION.name,
    );

    await userEvent.click(screen.getByText("Create"));

    expect(
      fetchMock.calls(`path:/api/dashboard`, { method: "POST" }),
    ).toHaveLength(1);

    // api called with typed form input
    expect(
      await fetchMock
        .lastCall(`path:/api/dashboard`, { method: "POST" })
        ?.request?.json(),
    ).toMatchObject({
      name: "My awesome dashboard title",
      collection_id: PERSONAL_COLLECTION.id,
    });

    // called prop with api response
    expect(onCreate).toHaveBeenCalledTimes(1);
    expect(onCreate).toHaveBeenLastCalledWith(mockResponseDashboard);
  });

  it('should support "isOpen" prop', () => {
    const { rerender } = setup({
      props: {
        isOpen: false,
      },
    });

    expect(screen.queryByText("New dashboard")).not.toBeInTheDocument();

    rerender(<CreateDashboardModal isOpen onCreate={jest.fn()} />);

    expect(screen.getByText("New dashboard")).toBeInTheDocument();
  });
});

function setup(
  {
    isLocaleLoading,
    props,
  }: {
    isLocaleLoading?: boolean;
    props?: Partial<CreateDashboardModalProps>;
  } = { isLocaleLoading: false, props: {} },
) {
  useLocaleMock.mockReturnValue({ isLocaleLoading });

  setupCollectionByIdEndpoint({ collections: COLLECTIONS });

  return renderWithSDKProviders(
    <CreateDashboardModal onCreate={jest.fn()} {...props} />,
    {
      sdkProviderProps: {
        authConfig: createMockSdkConfig(),
      },
      storeInitialState: {
        currentUser: CURRENT_USER,
      },
    },
  );
}
