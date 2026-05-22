// 模块用途：Axios HTTP客户端——统一封装请求拦截、响应拦截、错误处理
// 依赖组件：无
// 修改注意：BASE_URL根据环境变量切换，401时自动跳转登录页
import axios from 'axios';

const REQUEST_TIMEOUT_MS = 15_000;

const client = axios.create({
  baseURL: '/api/v1',
  timeout: REQUEST_TIMEOUT_MS,
  headers: { 'Content-Type': 'application/json' },
});

// 功能：请求拦截器——从Cookie读取XSRF-TOKEN并设置到请求头，Spring Security CSRF校验需要
client.interceptors.request.use((config) => {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  if (match) {
    config.headers['X-XSRF-TOKEN'] = match[1];
  }
  return config;
});

// 功能：响应拦截器——统一处理401未登录跳转、403无权限提示、409冲突
client.interceptors.response.use(
  (response) => {
    if (response.data && response.data.code && response.data.code !== 200) {
      return Promise.reject({ code: response.data.code, message: response.data.message || '请求失败', data: response.data.data });
    }
    return response.data;
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response;
      if (status === 401) {
        if (window.location.pathname === '/login') {
          return Promise.reject({ code: 401, message: data?.message || '用户名或密码错误' });
        }
        window.location.href = '/login';
      } else if (status === 403) {
        return Promise.reject({ code: 403, message: '无权访问' });
      } else if (status === 409) {
        return Promise.reject({ code: 409, message: data?.message || '数据已被他人修改', data });
      }
      return Promise.reject({ code: status, message: data?.message || '请求失败' });
    }
    return Promise.reject({ code: 0, message: '网络异常，请点击重试' });
  }
);

export default client;
