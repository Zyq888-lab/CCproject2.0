import { useState, useEffect, useRef } from 'react';
import client from '../api/client';

let cachedOptions = null;
let fetchPromise = null;

export default function useCategories() {
  const [options, setOptions] = useState(cachedOptions || []);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;

    if (cachedOptions) {
      setOptions(cachedOptions);
      return () => { mountedRef.current = false; };
    }

    if (!fetchPromise) {
      fetchPromise = client.get('/position-categories/list')
        .then((res) => {
          const list = (Array.isArray(res.data) ? res.data : [])
            .map((c) => ({ label: c.name, value: c.name }));
          cachedOptions = list;
          return list;
        })
        .catch(() => {
          fetchPromise = null;
          return [];
        });
    }

    fetchPromise.then((list) => {
      if (mountedRef.current) setOptions(list);
    });

    return () => { mountedRef.current = false; };
  }, []);

  return options;
}
