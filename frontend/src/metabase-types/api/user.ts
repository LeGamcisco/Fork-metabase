import type { CollectionId } from "./collection";
import type { DashboardId } from "./dashboard";
import type { PaginationRequest, PaginationResponse } from "./pagination";

export type UserId = number;
export type UserAttributeKey = string;
export type UserAttributeValue = string;
export type UserAttributeMap = Record<UserAttributeKey, UserAttributeValue>;

export type UserAttributeSource = "system" | "jwt" | "user";

type StructuredAttributeBase = {
  frozen: boolean;
  source: UserAttributeSource;
  value: UserAttributeValue;
};

export type StructuredUserAttribute = StructuredAttributeBase & {
  original?: StructuredAttributeBase; // this allows us to revert to a previous value
};

export type StructuredUserAttributes = Record<
  UserAttributeKey,
  StructuredUserAttribute
>;

export interface BaseUser {
  id: UserId;
  first_name: string | null;
  last_name: string | null;
  common_name: string;
  email: string;
  locale: string | null;
  is_active: boolean;
  is_qbnewb: boolean;
  is_superuser: boolean;

  date_joined: string;
  last_login: string;
  first_login: string;
  updated_at: string;
}

export interface User extends BaseUser {
  login_attributes: UserAttributeMap | null;
  structured_attributes?: StructuredUserAttributes;
  user_group_memberships?: { id: number; is_group_manager: boolean }[];
  is_installer: boolean;
  has_invited_second_user: boolean;
  has_question_and_dashboard: boolean;
  personal_collection_id: CollectionId;
  sso_source: "jwt" | "ldap" | "google" | "scim" | "saml" | null;
  custom_homepage: {
    dashboard_id: DashboardId;
  } | null;
}

export interface UserListResult {
  id: UserId;
  first_name: string | null;
  last_name: string | null;
  common_name: string;
  email: string;
  personal_collection_id: CollectionId;
  structured_attributes?: StructuredUserAttributes;
}

export interface UserListMetadata {
  total: number;
}

// Used when hydrating `creator` property
export type UserInfo = Pick<
  BaseUser,
  | "id"
  | "common_name"
  | "first_name"
  | "last_name"
  | "email"
  | "date_joined"
  | "last_login"
  | "is_superuser"
  | "is_qbnewb"
>;

export type UserListQuery = {
  recipients?: boolean;
} & PaginationRequest;

export type UserLoginHistoryItem = {
  timestamp: string;
  device_description: string;
  ip_address: string;
  location: string;
  active: boolean;
  timezone: string | null;
};

export type UserLoginHistory = UserLoginHistoryItem[];

export type CreateUserRequest = {
  email: string;
  first_name?: string;
  last_name?: string;
  user_group_memberships?: { id: number; is_group_manager: boolean }[];
  login_attributes?: UserAttributeMap;
  password?: string;
};

export type UpdatePasswordRequest = {
  id: UserId;
  password: string;
  old_password?: string;
};

export type ListUsersRequest = {
  status?: "deactivated" | "all";
  query?: string;
  group_id?: number;
  include_deactivated?: boolean;
} & PaginationRequest;

export type ListUsersResponse = {
  data: User[];
} & PaginationResponse;

export type UpdateUserRequest = {
  id: UserId;
  email?: string | null;
  first_name?: string | null;
  last_name?: string | null;
  locale?: string | null;
  is_group_manager?: boolean;
  is_superuser?: boolean;
  login_attributes?: UserAttributeMap | null;
  user_group_memberships?: { id: number; is_group_manager: boolean }[];
};

export type UserKeyValue =
  | { namespace: "test"; key: string; value: any }
  | { namespace: "indicator-menu"; key: string; value: string[] }
  | {
      namespace: "user_acknowledgement";
      key: string;
      value: boolean;
    }
  | {
      namespace: "last_download_format";
      key: string;
      value: {
        last_download_format: "csv" | "xlsx" | "json" | "png";
        last_table_download_format: "csv" | "xlsx" | "json";
      };
    };

export type UserKeyValueKey = Pick<UserKeyValue, "namespace" | "key">;

export type DeleteUserKeyValueRequest = UserKeyValueKey;

export type GetUserKeyValueRequest = UserKeyValueKey;

export type UpdateUserKeyValueRequest = UserKeyValue & {
  expires_at?: string;
};
